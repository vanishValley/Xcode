package com.xu.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条长期知识 = 一个原子事实。取代旧的 LongTermMemory.MemoryEntry。
 *
 * 相比旧结构,把治理需要的信息提成一等字段(旧版塞在 metadata Map 里,弱类型易漂移):
 *   scope      — 项目专属 / 全局通用(跨项目隔离)
 *   projectKey — PROJECT 时属于哪个仓库;GLOBAL 时为 ""
 *   source     — 谁写的(HUMAN /save 高信任 / AGENT 自动沉淀需审查)
 *   confidence — 置信度 0..1,治理门据此决定直接入库还是挂起
 *   createdAt  — 写入时间,检索时间衰减用
 *
 * record 不可变:字段即访问器(content()/scope()...),天然适合当持久化 + 检索的数据载体。
 */
public record MemoryRecord(
        String id,
        String content,
        MemoryScope scope,
        String projectKey,
        MemorySource source,
        double confidence,
        Instant createdAt
) {
    /** 新建候选的统一入口:自动生成 id/时间,GLOBAL 强制清空 projectKey。 */
    public static MemoryRecord create(String content, MemoryScope scope, String projectKey,
                                      MemorySource source, double confidence) {
        String id = "mem_" + UUID.randomUUID().toString().substring(0, 8);
        String pk = scope == MemoryScope.GLOBAL ? "" : (projectKey == null ? "" : projectKey);
        return new MemoryRecord(id, content, scope, pk, source, confidence, Instant.now());
    }

    /**
     * 当前仓库能否看到这条。
     *   GLOBAL  → 到哪都可见
     *   PROJECT → 要求当前仓库 key 与写入时的 projectKey 一致
     */
    public boolean visibleIn(String currentProjectKey) {
        if (scope == MemoryScope.GLOBAL) return true;
        return currentProjectKey != null && currentProjectKey.equals(projectKey);
    }
}
