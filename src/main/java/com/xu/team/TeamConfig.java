package com.xu.team;

import java.time.Duration;

/** 有界配置；系统属性只在创建团队时读取一次。 */
public record TeamConfig(int concurrency, int maxMembers, int maxCreations,
                         int queueCapacity, int mailboxCapacity, int maxCalls,
                         long maxTokens, int outputTokens, Duration deadline,
                         Duration shutdownTimeout) {
    public TeamConfig {
        if (concurrency < 1 || maxMembers < concurrency || maxCreations < maxMembers
                || queueCapacity < 1 || mailboxCapacity < 1 || maxCalls < 1
                || maxTokens < 1 || outputTokens < 1 || outputTokens > 8192
                || deadline.isNegative() || deadline.isZero()
                || shutdownTimeout.isNegative()) throw new IllegalArgumentException("Invalid Team limits");
    }
    public static TeamConfig defaults() {
        return new TeamConfig(4, 8, 12, 8, 32,
                Integer.getInteger("xcode.team.maxCalls", 100),
                Long.getLong("xcode.team.maxTokens", 500_000L),
                Integer.getInteger("xcode.team.outputTokens", 4096),
                Duration.ofSeconds(Long.getLong("xcode.team.timeoutSeconds", 900L)),
                Duration.ofSeconds(5));
    }
}
