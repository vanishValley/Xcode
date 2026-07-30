package com.xu.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 治理门默认实现(v1)。
 *
 * 规则(按优先级从上到下):
 *   1. 精确重复(已有完全相同 content)      → REJECT
 *   2. 近似重复(与某条 token 重叠率 ≥ 0.8)   → MERGE(视为已知,不新增)
 *   3. HUMAN 来源(/save)                    → COMMIT(高信任逃生舱,只受上面去重约束)
 *   4. AGENT 来源:置信 ≥ 0.6 → COMMIT;否则 → DEFER(挂起等人工)
 *
 * 诚实的边界(面试要主动讲):
 *   真正的"语义矛盾"检测(A 说用 Java17、B 说用 Java21 —— 内容相反而非相似)v1 没做。
 *   token 重叠只能识别"像不像",识别不了"反不反"。语义裁决留 v2 上 LLM。
 *   所以 v1 的 DEFER 主要用于"低置信 AGENT 候选",不是"检测到冲突"。
 */
public class DefaultGovernanceGate implements GovernanceGate {

    private static final double NEAR_DUP_OVERLAP = 0.8;
    private static final double AGENT_COMMIT_CONFIDENCE = 0.6;

    @Override
    public Decision evaluate(MemoryRecord candidate, List<MemoryRecord> existing) {
        // 1. 精确重复
        for (MemoryRecord e : existing) {
            if (e.content().equals(candidate.content())) return Decision.REJECT;
        }
        // 2. 近似重复(Jaccard 重叠)
        Set<String> candTokens = KeywordRetriever.tokenize(candidate.content());
        for (MemoryRecord e : existing) {
            if (overlap(candTokens, KeywordRetriever.tokenize(e.content())) >= NEAR_DUP_OVERLAP) {
                return Decision.MERGE;
            }
        }
        // 3. 手动 /save:高信任,直接进
        if (candidate.source() == MemorySource.HUMAN) return Decision.COMMIT;
        // 4. agent 自动:够置信才进,否则挂起
        return candidate.confidence() >= AGENT_COMMIT_CONFIDENCE
                ? Decision.COMMIT : Decision.DEFER;
    }

    /** Jaccard 重叠率 = 交集 / 并集。任一为空返回 0。 */
    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        long inter = a.stream().filter(b::contains).count();
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter / union.size();
    }
}
