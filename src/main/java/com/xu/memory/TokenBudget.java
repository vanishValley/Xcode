package com.xu.memory;

import com.xu.llm.LlmClient.Message;

import java.util.List;

/**
 * 估算上下文 Token 用量并决定是否压缩。
 *
 * <p>当前按中英文混合文本约 2.5 字符/Token 做保守估算。占用达到 80% 时提前压缩，
 * 为后续工具结果和模型输出保留空间；该估算用于容量保护，不追求计费级精度。</p>
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

    /** @param maxContextWindow 模型最大上下文窗口，例如 DeepSeek 默认 128K */
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
