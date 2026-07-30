package com.xu.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xu.util.FileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期知识的纯存储层 —— 只负责:存取、结构化过滤(按 scope/仓库)、容量淘汰、落盘。
 *
 * 从旧 LongTermMemory 拆出"存"的部分。另外两块拆走:
 *   - "检索打分" → KeywordRetriever
 *   - "写入去重/冲突判断" → GovernanceGate
 *
 * 关键破环:本类【不认识 jieba、不打相关性分】。于是 KeywordRetriever 单向只读依赖本类,
 * 本类不再回调检索逻辑 —— 消除旧 LongTermMemory.search ↔ MemoryRetriever.score 的双向环。
 *
 * 存储:<projectDataDir>/knowledge.json,写入走 FileUtils 原子写(tmp+rename)。
 */
public class KnowledgeStore {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeStore.class);

    /** 容量上限:防记忆膨胀稀释检索精度。超了淘汰最老的一条(存储层职责;去重不在这)。 */
    private static final int MAX_ENTRIES = 50;
    private static final String FILE_NAME = "knowledge.json";

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path storageFile;   // null = 内存态(测试用,不落盘)

    /** 落盘态:指定项目数据目录。 */
    public KnowledgeStore(Path storageDir) {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.storageFile = storageDir.resolve(FILE_NAME);
        loadFromDisk();
    }

    /** 内存态:测试用,不落盘。 */
    public KnowledgeStore() {
        this.mapper = new ObjectMapper();
        this.storageFile = null;
    }

    // ---- 写(原样存,判断留给治理门)----

    /** upsert by id + 超容量淘汰最老 + 落盘。去重/冲突是 GovernanceGate 的事,这里不判。 */
    public MemoryRecord put(MemoryRecord r) {
        records.put(r.id(), r);
        if (records.size() > MAX_ENTRIES) evictOldest();
        persist();
        return r;
    }

    // ---- 读(纯结构化过滤,无相关性打分)----

    /** 当前仓库可见的全部记录(GLOBAL + 本项目 PROJECT)。Retriever 从这里取候选打分。 */
    public List<MemoryRecord> visible(String projectKey) {
        return records.values().stream()
                .filter(r -> r.visibleIn(projectKey))
                .toList();
    }

    /** 与候选同 scope+同仓库 的现有记录,供治理门做去重/冲突判断。 */
    public List<MemoryRecord> siblingsOf(MemoryRecord candidate) {
        return records.values().stream()
                .filter(r -> r.scope() == candidate.scope()
                        && Objects.equals(r.projectKey(), candidate.projectKey()))
                .toList();
    }

    /** 列出当前仓库可见记录,按时间倒序(CLI /memory 展示用)。 */
    public List<MemoryRecord> listAll(String projectKey) {
        return records.values().stream()
                .filter(r -> r.visibleIn(projectKey))
                .sorted(Comparator.comparing(MemoryRecord::createdAt).reversed())
                .toList();
    }

    public Optional<MemoryRecord> get(String id) {
        return Optional.ofNullable(records.get(id));
    }

    // ---- 删 ----

    public boolean delete(String id) {
        if (records.remove(id) != null) { persist(); return true; }
        return false;
    }

    public void clear() { records.clear(); persist(); }

    public int size() { return records.size(); }

    // ---- 内部 ----

    private void evictOldest() {
        records.values().stream()
                .min(Comparator.comparing(MemoryRecord::createdAt))
                .ifPresent(oldest -> records.remove(oldest.id()));
    }

    private void persist() {
        if (storageFile == null) return;
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (MemoryRecord r : records.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id());
                m.put("content", r.content());
                m.put("scope", r.scope().name());
                m.put("projectKey", r.projectKey());
                m.put("source", r.source().name());
                m.put("confidence", r.confidence());
                m.put("createdAt", r.createdAt().toString());
                data.add(m);
            }
            // 复用 FileUtils 原子写:tmp+rename,和 SessionStore/PlanStore 同一套,杜绝半成品文件
            FileUtils.atomicWrite(storageFile, mapper.writeValueAsString(data));
        } catch (IOException ex) {
            logger.error("持久化失败: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (storageFile == null || !Files.exists(storageFile)) return;
        try {
            List<Map<String, Object>> data = mapper.readValue(storageFile.toFile(), List.class);
            for (Map<String, Object> m : data) {
                double conf = m.get("confidence") instanceof Number n ? n.doubleValue() : 0.9;
                MemoryRecord r = new MemoryRecord(
                        (String) m.get("id"),
                        (String) m.get("content"),
                        MemoryScope.valueOf((String) m.getOrDefault("scope", "PROJECT")),
                        String.valueOf(m.getOrDefault("projectKey", "")),
                        MemorySource.valueOf((String) m.getOrDefault("source", "HUMAN")),
                        conf,
                        Instant.parse((String) m.get("createdAt")));
                records.put(r.id(), r);
            }
        } catch (Exception e) {
            logger.error("加载失败: {}", e.getMessage());
        }
    }
}
