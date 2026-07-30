package com.xu.memory;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 关键词检索器 —— jieba 分词 + 命中率×时间衰减打分。打分逻辑搬自旧 MemoryRetriever,不变。
 *
 * 破环:本类单向只读依赖 KnowledgeStore(调 visible() 取候选),Store 不再回调本类。
 * 旧结构是 LongTermMemory.search → MemoryRetriever.score → 又回到 LongTermMemory 的双向环。
 *
 * 打分公式:
 *   命中率 = 命中的 token 数 / 总 token 数         (匹配密度,不是 0/1 的 contains)
 *   时间衰减 = max(0.5, 1 - 距今小时数 / 24)        (旧的降权但不归零,下限 0.5)
 *   最终分 = 命中率 × 时间衰减
 *
 * 衰减留下限的原因:一条"这仓库测试要设 JAVA_HOME"的老约定,恰恰最该记住,不能因"老"被压到 0 捞不出。
 */
public class KeywordRetriever implements Retriever {

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private final KnowledgeStore store;   // 只读依赖

    public KeywordRetriever(KnowledgeStore store) {
        this.store = store;
    }

    @Override
    public List<MemoryRecord> retrieve(String query, String projectKey, int topK) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return List.of();

        // 时钟只读一次:排序期间每条分数稳定。若在 comparator 里现算,时间衰减会让分数随时钟漂移,
        // 可能违反 Comparator 契约(抛 IllegalArgumentException)。
        long now = System.currentTimeMillis();

        record Scored(MemoryRecord record, double score) {}
        return store.visible(projectKey).stream()          // 候选 = 当前仓库可见的
                .map(r -> new Scored(r, score(r, tokens, now)))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::record)
                .toList();
    }

    // ────── 分词 + 打分(static,可独立单测)──────

    /** jieba 分词,过滤单字和纯标点,英文转小写。 */
    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) return tokens;
        String lower = query.toLowerCase(Locale.ROOT).trim();
        for (String word : SEGMENTER.sentenceProcess(lower)) {
            String w = word.trim();
            if (w.length() >= 2 && !isPunctuationOnly(w)) tokens.add(w);
        }
        return tokens;
    }

    /** now 由调用方统一传入,保证一次检索内时钟一致。 */
    static double score(MemoryRecord r, Set<String> tokens, long now) {
        if (tokens.isEmpty()) return 0;
        String content = r.content().toLowerCase(Locale.ROOT);

        int matched = 0;
        for (String t : tokens) if (content.contains(t)) matched++;
        if (matched == 0) return 0;

        double hitRate = (double) matched / tokens.size();
        long ageMs = now - r.createdAt().toEpochMilli();
        double ageHours = ageMs / 3_600_000.0;          // 1000 * 60 * 60
        double decay = Math.max(0.5, 1.0 - ageHours / 24.0);
        return hitRate * decay;
    }

    private static boolean isPunctuationOnly(String s) {
        return s.codePoints().allMatch(cp ->
                !Character.isLetterOrDigit(cp)
                && Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN);
    }
}
