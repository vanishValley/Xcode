package com.xu.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.xu.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆组件。
 *
 * <p>对外只有两条主路径：
 * <ul>
 *   <li>{@link #save(MemoryRecord)}：统一接收 HUMAN/AGENT 候选，再按来源应用写入策略。</li>
 *   <li>{@link #retrieve(String, String)}：按作用域过滤并做关键词相关性排序。</li>
 * </ul>
 *
 * <p>当前数据量最多 50 条，文件存储、关键词检索和写入治理都是这个小型组件的实现细节，
 * 暂不拆成只有一个实现的接口。将来真的接入向量库时，再从这里抽出存储或检索边界。
 */
public final class LongTermMemory {

    public enum SaveResult { COMMIT, MERGE, DEFER, REJECT }

    private static final Logger logger =
            LoggerFactory.getLogger(LongTermMemory.class);

    private static final String FILE_NAME = "knowledge.json";
    private static final int MAX_ENTRIES = 50;
    private static final int MAX_AGENT_ENTRIES = 25;
    private static final int TOP_K = 3;
    private static final double HUMAN_NEAR_DUP_OVERLAP = 0.8;
    private static final double AGENT_OVERWRITE_OVERLAP = 0.7;
    private static final double AGENT_COMMIT_CONFIDENCE = 0.6;

    private final Map<String, MemoryRecord> records =
            new ConcurrentHashMap<>();
    private final List<MemoryRecord> reviewQueue = new ArrayList<>();
    private final ObjectMapper mapper;
    private final Path storageFile;

    private LongTermMemory(Path storageDir) {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.storageFile = storageDir == null
                ? null
                : storageDir.resolve(FILE_NAME);
        loadFromDisk();
        // 在 UI 启动前预热 jieba，避免首次检索时向控制台打印初始化日志。
        SegmenterHolder.INSTANCE.getClass();
    }

    /** 落盘态：知识保存到 {@code <projectDataDir>/knowledge.json}。 */
    public static LongTermMemory create(Path storageDir) {
        return new LongTermMemory(
                Objects.requireNonNull(storageDir, "storageDir"));
    }

    /** 内存态：供子系统测试使用。 */
    public static LongTermMemory inMemory() {
        return new LongTermMemory(null);
    }

    /**
     * 统一写入口。HUMAN 和 AGENT 共用入口，但信任策略不同：
     * HUMAN 只做去重；AGENT 还要检查置信度、更新旧经验并限制自动条目数量。
     */
    public SaveResult save(MemoryRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return candidate.source() == MemorySource.HUMAN
                ? saveHuman(candidate)
                : saveAgent(candidate);
    }

    private SaveResult saveHuman(MemoryRecord candidate) {
        List<MemoryRecord> existing = siblingsOf(candidate);
        for (MemoryRecord record : existing) {
            if (record.content().equals(candidate.content())) {
                return SaveResult.REJECT;
            }
        }

        Set<String> candidateTokens = tokenize(candidate.content());
        for (MemoryRecord record : existing) {
            if (overlap(candidateTokens, tokenize(record.content()))
                    >= HUMAN_NEAR_DUP_OVERLAP) {
                return SaveResult.MERGE;
            }
        }

        put(candidate);
        return SaveResult.COMMIT;
    }

    private SaveResult saveAgent(MemoryRecord candidate) {
        List<MemoryRecord> existing = visible(candidate.projectKey());
        for (MemoryRecord record : existing) {
            if (record.content().equals(candidate.content())) {
                return SaveResult.REJECT;
            }
        }

        if (candidate.confidence() < AGENT_COMMIT_CONFIDENCE) {
            Set<String> candidateTokens = tokenize(candidate.content());
            for (MemoryRecord record : siblingsOf(candidate)) {
                if (overlap(candidateTokens, tokenize(record.content()))
                        >= HUMAN_NEAR_DUP_OVERLAP) {
                    return SaveResult.MERGE;
                }
            }
            reviewQueue.add(candidate);
            return SaveResult.DEFER;
        }

        Set<String> candidateTokens = tokenize(candidate.content());
        MemoryRecord replaced = existing.stream()
                .filter(record -> record.source() == MemorySource.AGENT)
                .filter(record -> overlap(
                        candidateTokens,
                        tokenize(record.content()))
                        >= AGENT_OVERWRITE_OVERLAP)
                .findFirst()
                .orElse(null);
        if (replaced != null) {
            records.remove(replaced.id());
        }

        long agentCount = records.values().stream()
                .filter(record -> record.source() == MemorySource.AGENT)
                .count();
        if (agentCount >= MAX_AGENT_ENTRIES) {
            records.values().stream()
                    .filter(record ->
                            record.source() == MemorySource.AGENT)
                    .min(Comparator.comparing(MemoryRecord::createdAt))
                    .ifPresent(oldest -> records.remove(oldest.id()));
        }

        put(candidate);
        return SaveResult.COMMIT;
    }

    /** 检索当前项目可见的最多三条相关记忆。 */
    public List<MemoryRecord> retrieve(
            String query,
            String projectKey) {
        return retrieve(query, projectKey, TOP_K);
    }

    List<MemoryRecord> retrieve(
            String query,
            String projectKey,
            int topK) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        record Scored(MemoryRecord record, double score) {}
        return visible(projectKey).stream()
                .map(record ->
                        new Scored(record, score(record, tokens, now)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score)
                        .reversed())
                .limit(topK)
                .map(Scored::record)
                .toList();
    }

    /** CLI 展示：GLOBAL + 当前项目记忆，按时间倒序。 */
    public List<MemoryRecord> list(String projectKey) {
        return visible(projectKey).stream()
                .sorted(Comparator.comparing(MemoryRecord::createdAt)
                        .reversed())
                .toList();
    }

    public List<MemoryRecord> pendingReview() {
        return List.copyOf(reviewQueue);
    }

    public void clear() {
        records.clear();
        reviewQueue.clear();
        persist();
    }

    private void put(MemoryRecord record) {
        records.put(record.id(), record);
        if (records.size() > MAX_ENTRIES) {
            records.values().stream()
                    .min(Comparator.comparing(MemoryRecord::createdAt))
                    .ifPresent(oldest -> records.remove(oldest.id()));
        }
        persist();
    }

    private List<MemoryRecord> visible(String projectKey) {
        return records.values().stream()
                .filter(record -> record.visibleIn(projectKey))
                .toList();
    }

    private List<MemoryRecord> siblingsOf(MemoryRecord candidate) {
        return records.values().stream()
                .filter(record ->
                        record.scope() == candidate.scope()
                                && Objects.equals(
                                record.projectKey(),
                                candidate.projectKey()))
                .toList();
    }

    private void persist() {
        if (storageFile == null) {
            return;
        }
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (MemoryRecord record : records.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", record.id());
                item.put("content", record.content());
                item.put("scope", record.scope().name());
                item.put("projectKey", record.projectKey());
                item.put("source", record.source().name());
                item.put("confidence", record.confidence());
                item.put("createdAt", record.createdAt().toString());
                data.add(item);
            }
            FileUtils.atomicWrite(
                    storageFile,
                    mapper.writeValueAsString(data));
        } catch (IOException error) {
            logger.error("长期记忆持久化失败: {}", error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (storageFile == null || !Files.exists(storageFile)) {
            return;
        }
        try {
            List<Map<String, Object>> data =
                    mapper.readValue(storageFile.toFile(), List.class);
            for (Map<String, Object> item : data) {
                double confidence =
                        item.get("confidence") instanceof Number number
                                ? number.doubleValue()
                                : 0.9;
                MemoryRecord record = new MemoryRecord(
                        (String) item.get("id"),
                        (String) item.get("content"),
                        MemoryScope.valueOf((String) item.getOrDefault(
                                "scope", "PROJECT")),
                        String.valueOf(item.getOrDefault(
                                "projectKey", "")),
                        MemorySource.valueOf((String) item.getOrDefault(
                                "source", "HUMAN")),
                        confidence,
                        Instant.parse((String) item.get("createdAt")));
                records.put(record.id(), record);
            }
        } catch (Exception error) {
            logger.error("长期记忆加载失败: {}", error.getMessage());
        }
    }

    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        String lower = query.toLowerCase(Locale.ROOT).trim();
        for (String word :
                SegmenterHolder.INSTANCE.sentenceProcess(lower)) {
            String token = word.trim();
            if (token.length() >= 2
                    && !isPunctuationOnly(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    static double score(
            MemoryRecord record,
            Set<String> tokens,
            long now) {
        if (tokens.isEmpty()) {
            return 0;
        }
        String content = record.content().toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String token : tokens) {
            if (content.contains(token)) {
                matched++;
            }
        }
        if (matched == 0) {
            return 0;
        }

        double hitRate = (double) matched / tokens.size();
        long ageMillis = now - record.createdAt().toEpochMilli();
        double ageHours = ageMillis / 3_600_000.0;
        double decay = Math.max(0.5, 1.0 - ageHours / 24.0);
        return hitRate * decay;
    }

    private static double overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long intersection =
                left.stream().filter(right::contains).count();
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection / union.size();
    }

    private static boolean isPunctuationOnly(String value) {
        return value.codePoints().allMatch(codePoint ->
                !Character.isLetterOrDigit(codePoint)
                        && Character.UnicodeScript.of(codePoint)
                        != Character.UnicodeScript.HAN);
    }

    private static final class SegmenterHolder {
        private static final JiebaSegmenter INSTANCE =
                initializeSilently();

        private static synchronized JiebaSegmenter initializeSilently() {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try (PrintStream sink =
                         new PrintStream(OutputStream.nullOutputStream())) {
                System.setOut(sink);
                System.setErr(sink);
                JiebaSegmenter segmenter = new JiebaSegmenter();
                segmenter.sentenceProcess("初始化");
                return segmenter;
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }
}
