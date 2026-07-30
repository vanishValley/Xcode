package com.xu.memory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期知识子系统门面 —— 把 存储/检索/治理门/待确认队列 组合起来,对上只暴露两条路径:
 *
 *   写路径 save()     : 候选 → 治理门 evaluate → COMMIT 入库 / DEFER 挂起 / MERGE|REJECT 丢弃
 *   读路径 retrieve() : 委托 Retriever 打分取 top-K
 *
 * 这一层让 MemoryManager 不用关心内部三个组件怎么协作,只管"存一条""捞几条"。
 * 组件之间依赖单向:KnowledgeBase → {Store, Retriever→Store, Gate};无环。
 */
public class KnowledgeBase {

    /** 注入 prompt 的相关记忆条数上限(捞太多会稀释重点、挤占上下文)。 */
    private static final int TOP_K = 3;

    private final KnowledgeStore store;
    private final Retriever retriever;
    private final GovernanceGate gate;
    /** DEFER 挂起区:治理拿不准的候选,等人工 /memory review 确认(内存态,进程内)。 */
    private final List<MemoryRecord> reviewQueue = new ArrayList<>();

    public KnowledgeBase(KnowledgeStore store, Retriever retriever, GovernanceGate gate) {
        this.store = store;
        this.retriever = retriever;
        this.gate = gate;
    }

    // ---- 写路径 ----

    /** 提交一条候选,返回治理决策(仅 COMMIT 真正落库)。 */
    public GovernanceGate.Decision save(MemoryRecord candidate) {
        GovernanceGate.Decision d = gate.evaluate(candidate, store.siblingsOf(candidate));
        switch (d) {
            case COMMIT -> store.put(candidate);
            case DEFER -> reviewQueue.add(candidate);
            case MERGE, REJECT -> { /* 已知/噪声:不新增 */ }
        }
        return d;
    }

    /** 便捷写:手动 /save 语义(HUMAN 来源,置信 0.9)。 */
    public GovernanceGate.Decision saveHuman(String content, MemoryScope scope, String projectKey) {
        return save(MemoryRecord.create(content, scope, projectKey, MemorySource.HUMAN, 0.9));
    }

    // ---- 自动沉淀 ----

    private static final int MAX_AGENT_ENTRIES = 25;
    private static final double OVERWRITE_OVERLAP = 0.7;

    /**
     * 自动沉淀:AGENT 来源,去重+覆盖+数量上限,不入治理门。
     *
     * 和手动 /save 的区别:
     *   - 不经过 GovernanceGate(AGENT 来源的信任基于"踩坑→修正→成功"的触发条件,不靠置信打分)
     *   - 与已有 token 重叠 ≥ 0.7 → 替换旧的(知识更新,不是新增)
     *   - 自动条目 ≤ 25,超了淘汰最老的 agent 来源(不动 HUMAN 来源)
     */
    public void saveAgent(String content, String projectKey) {
        List<MemoryRecord> existing = store.visible(projectKey);

        // ① 精确重复 → 跳过
        for (MemoryRecord r : existing) {
            if (r.content().equals(content)) return;
        }

        // ② 近似重复 → 覆盖旧的
        java.util.Set<String> newTokens = KeywordRetriever.tokenize(content);
        for (MemoryRecord r : existing) {
            if (r.source() == MemorySource.AGENT
                    && overlap(newTokens, KeywordRetriever.tokenize(r.content())) >= OVERWRITE_OVERLAP) {
                store.delete(r.id());
                break; // 只覆盖第一条匹配的
            }
        }

        // ③ agent 来源数量上限
        long agentCount = existing.stream()
                .filter(r -> r.source() == MemorySource.AGENT).count();
        if (agentCount >= MAX_AGENT_ENTRIES) {
            existing.stream()
                    .filter(r -> r.source() == MemorySource.AGENT)
                    .min(java.util.Comparator.comparing(MemoryRecord::createdAt))
                    .ifPresent(oldest -> store.delete(oldest.id()));
        }

        store.put(MemoryRecord.create(content, MemoryScope.PROJECT, projectKey,
                MemorySource.AGENT, 0.5));
    }

    /** Jaccard 重叠率 */
    private static double overlap(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        long inter = a.stream().filter(b::contains).count();
        java.util.Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return (double) inter / union.size();
    }

    // ---- 读路径 ----

    public List<MemoryRecord> retrieve(String query, String projectKey) {
        return retriever.retrieve(query, projectKey, TOP_K);
    }

    // ---- CLI 辅助 ----

    public List<MemoryRecord> list(String projectKey) { return store.listAll(projectKey); }
    public List<MemoryRecord> pendingReview() { return List.copyOf(reviewQueue); }
    public void clear() { store.clear(); reviewQueue.clear(); }

    // ---- 便捷工厂 ----

    /** 落盘态:知识存到 <dir>/knowledge.json。 */
    public static KnowledgeBase create(Path dir) {
        KnowledgeStore store = new KnowledgeStore(dir);
        return new KnowledgeBase(store, new KeywordRetriever(store), new DefaultGovernanceGate());
    }

    /** 内存态:测试用。 */
    public static KnowledgeBase inMemory() {
        KnowledgeStore store = new KnowledgeStore();
        return new KnowledgeBase(store, new KeywordRetriever(store), new DefaultGovernanceGate());
    }
}
