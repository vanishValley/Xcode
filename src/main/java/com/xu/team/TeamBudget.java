package com.xu.team;

import java.util.Map;

/** 请求前原子预留，返回后结算；输入估算不是计费级硬保证。 */
public final class TeamBudget {
    private final TeamConfig config;
    private final long expiresAt;
    private int calls;
    private long used, reserved;
    public TeamBudget(TeamConfig config) {
        this.config = config;
        expiresAt = System.nanoTime() + config.deadline().toNanos();
    }
    public boolean expired() { return System.nanoTime() >= expiresAt; }
    public synchronized long reserve(long inputEstimate) {
        if (expired() || calls >= config.maxCalls())
            throw new TeamException("BUDGET_EXCEEDED", "团队执行时间或模型调用预算已耗尽");
        long amount = Math.addExact(inputEstimate, config.outputTokens());
        if (used + reserved + amount > config.maxTokens())
            throw new TeamException("BUDGET_EXCEEDED", "团队 token 预算不足");
        calls++; reserved += amount;
        return amount;
    }
    public synchronized void settle(long amount, long actual) {
        reserved -= amount;
        // 用量缺失或请求失败时仍保留预估成本，不当作免费调用。
        used += actual > 0 ? actual : amount;
    }
    public synchronized Map<String, Object> snapshot() {
        return Map.of("calls", calls, "usedTokens", used, "reservedTokens", reserved);
    }
}
