package com.xu.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * 不可变的原子长期记忆。作用域、来源、置信度和创建时间均为强类型字段，
 * 供跨项目隔离、写入治理和检索时间衰减使用。
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
    /** 创建记忆候选；自动生成 ID 和时间，全局记忆不绑定项目。 */
    public static MemoryRecord create(String content, MemoryScope scope, String projectKey,
                                      MemorySource source, double confidence) {
        String id = "mem_" + UUID.randomUUID().toString().substring(0, 8);
        String pk = scope == MemoryScope.GLOBAL ? "" : (projectKey == null ? "" : projectKey);
        return new MemoryRecord(id, content, scope, pk, source, confidence, Instant.now());
    }

    /**
     * 判断当前项目能否读取此记忆：全局记忆始终可见，项目记忆必须匹配项目标识。
     */
    public boolean visibleIn(String currentProjectKey) {
        if (scope == MemoryScope.GLOBAL) return true;
        return currentProjectKey != null && currentProjectKey.equals(projectKey);
    }
}
