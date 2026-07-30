package com.xu.memory;

import com.xu.llm.LlmClient.Message;

import java.util.List;

/**
 * Token 预算管理 —— 估算上下文 Token 用量，判断是否需要压缩。
 *
 * 为什么不用精确 Token 计数？
 *   精确计数需要 tiktoken 库（Python），Java 生态没有对等的实现。
 *   业界通用折中：字符数 ÷ 经验系数。
 *   中英文混合场景下系数 ≈ 2.5，误差 ±15%。
 *
 * 为什么压缩阈值是 80% 而不是 100%？
 *   100% 意味着请求发出去的时候上下文是满的——工具返回没地方放，
 *   LLM 推理输出没 buffer。80% 触发压缩，留 20% 给工具返回和 LLM 输出。
 *
 * 面试材料：
 *   "Token 预算不是精确计算，是工程估算。关键是保下限而不是争上限——
 *   系数总是偏保守（估计值偏大），触发压缩偏早不偏晚。"
 */
public class TokenBudget {

    /** 模型上下文窗口（Token 数） */
    private final long maxContextWindow;

    /** 安全缓冲：留给 LLM 推理输出，不参与历史预算 */
    private static final long OUTPUT_BUFFER = 20_000;

    /** 压缩触发阈值 */
    private static final double COMPRESS_THRESHOLD = 0.80;

    /** 中英混合文本的字符→Token 换算系数 */
    private static final double CHARS_PER_TOKEN = 2.5;

    /**
     * 构造函数
     * @param maxContextWindow 模型的最大上下文窗口，如 DeepSeek 默认 128K
     */
    public TokenBudget(long maxContextWindow) {
        this.maxContextWindow = maxContextWindow;
    }

    /** DeepSeek 128K 的默认构造 */
    public TokenBudget() {
        this(128_000);
    }

    // ────── 核心方法 ──────

    /** 估算消息列表的 Token 数 */
    public long estimateTokens(List<Message> messages) {
        long totalChars = 0;
        for (Message msg : messages) {
            if (msg.content != null) totalChars += msg.content.length();
            if (msg.role != null) totalChars += msg.role.length();
            // tool_calls 的 JSON 序列化会由 LLM 自行处理，这里粗略加 200 字符/调用
            if (msg.toolCalls != null) totalChars += msg.toolCalls.size() * 200L;
        }
        return (long) (totalChars / CHARS_PER_TOKEN);
    }

    /** 估算工具返回的 Token 数 */
    public long estimateToolResult(String toolOutput) {
        if (toolOutput == null || toolOutput.isEmpty()) return 0;
        return (long) (toolOutput.length() / CHARS_PER_TOKEN);
    }

    /**
     * 当前上下文占用百分比。
     * 结果可能 > 1.0（已经超了）。
     */
    public double usagePercent(List<Message> messages) {
        return (double) estimateTokens(messages) / effectiveWindow();
    }

    /** 是否需要触发压缩 */
    public boolean shouldCompress(List<Message> messages) {
        return usagePercent(messages) > COMPRESS_THRESHOLD;
    }

    /** 目前还能塞多少 Token 的工具返回 */
    public long budgetForTools(List<Message> messages) {
        long used = estimateTokens(messages);
        long effective = effectiveWindow();
        // 留 20% 缓冲给 LLM 输出
        long available = (long) (effective * COMPRESS_THRESHOLD) - used;
        return Math.max(0, available);
    }

    /** 剩余总预算（用于日志/观测） */
    public long remainingBudget(List<Message> messages) {
        long used = estimateTokens(messages);
        return Math.max(0, effectiveWindow() - used);
    }

    // ────── 工具方法 ──────

    /** 真实可用的上下文窗口（总窗口 - 输出缓冲） */
    private long effectiveWindow() {
        return maxContextWindow - OUTPUT_BUFFER;
    }

    public long getMaxContextWindow() { return maxContextWindow; }
}
