package com.xu.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

/**
 * 记录 Coding Agent 的总体运行数据，例如任务数、调用耗时、Token 和工具调用次数。
 *
 * <p>指标回答“最近整体是否变慢、失败是否增多”；单次任务为什么失败则通过 Span
 * 和日志排查。这里不记录 trace_id、task_id 等每次都不同的值，避免指标维度过多。</p>
 */
public final class AgentMetrics {

    private static final AgentMetrics NOOP = new AgentMetrics();

    private final LongCounter taskCount;
    private final LongHistogram taskDuration;
    private final LongCounter llmCallCount;
    private final LongHistogram llmCallDuration;
    private final LongCounter tokenUsage;
    private final LongCounter toolCallCount;
    private final LongHistogram toolCallDuration;

    /** 创建一个不记录任何指标的空实现，供 Tracing.noop() 使用。 */
    private AgentMetrics() {
        taskCount = null;
        taskDuration = null;
        llmCallCount = null;
        llmCallDuration = null;
        tokenUsage = null;
        toolCallCount = null;
        toolCallDuration = null;
    }

    /**
     * 应用启动时调用，通过 OpenTelemetry Meter 创建项目需要的计数器和耗时分布。
     *
     * @param meter OpenTelemetry 的指标创建器
     */
    AgentMetrics(Meter meter) {
        taskCount = meter.counterBuilder("coding.task.count")
                .setDescription("Completed Coding Agent tasks")
                .build();
        taskDuration = meter.histogramBuilder("coding.task.duration")
                .ofLongs()
                .setUnit("ms")
                .setDescription("End-to-end Coding Agent task duration")
                .build();
        llmCallCount = meter.counterBuilder("gen_ai.client.operation.count")
                .setDescription("LLM client operations")
                .build();
        llmCallDuration = meter
                .histogramBuilder("gen_ai.client.operation.duration")
                .ofLongs()
                .setUnit("ms")
                .setDescription("LLM client operation duration")
                .build();
        tokenUsage = meter.counterBuilder("gen_ai.client.token.usage")
                .setUnit("{token}")
                .setDescription("LLM token usage")
                .build();
        toolCallCount = meter.counterBuilder("agent.tool.call.count")
                .setDescription("Agent-side tool calls")
                .build();
        toolCallDuration = meter
                .histogramBuilder("agent.tool.call.duration")
                .ofLongs()
                .setUnit("ms")
                .setDescription("Agent-side tool call duration")
                .build();
    }

    /** 返回不记录数据的指标对象，让业务代码无需判断指标功能是否开启。 */
    static AgentMetrics noop() {
        return NOOP;
    }

    /**
     * 一个完整 Coding Task 结束时调用，同时记录任务次数和端到端耗时。
     *
     * @param mode 执行模式，例如 agent 或 plan
     * @param outcome 执行结果，例如 SUCCESS 或 FAILED
     * @param durationMillis 整个任务的毫秒耗时
     */
    public void recordTask(
            String mode,
            String outcome,
            long durationMillis) {
        if (taskCount == null) return;
        Attributes attributes = Attributes.builder()
                .put("task.mode", value(mode))
                .put("task.outcome", value(outcome))
                .build();
        taskCount.add(1, attributes);
        taskDuration.record(Math.max(0L, durationMillis), attributes);
    }

    /**
     * 每次 LLM 请求结束时调用，记录调用次数、耗时以及输入输出 Token。
     *
     * @param model 本次请求使用的模型
     * @param outcome 请求结果
     * @param durationMillis 请求毫秒耗时
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     */
    public void recordLlm(
            String model,
            String outcome,
            long durationMillis,
            long inputTokens,
            long outputTokens) {
        if (llmCallCount == null) return;
        Attributes attributes = Attributes.builder()
                .put("gen_ai.request.model", value(model))
                .put("operation.outcome", value(outcome))
                .build();
        llmCallCount.add(1, attributes);
        llmCallDuration.record(Math.max(0L, durationMillis), attributes);
        recordTokens(inputTokens, outputTokens);
    }

    /**
     * 每次本地工具或 MCP 工具调用结束时调用，记录次数、耗时和结果类型。
     *
     * @param category 工具类别，例如 local 或 mcp
     * @param outcome 调用结果
     * @param errorType 失败类型；成功时通常为空
     * @param durationMillis 工具调用的毫秒耗时
     */
    public void recordTool(
            String category,
            String outcome,
            String errorType,
            long durationMillis) {
        if (toolCallCount == null) return;
        Attributes attributes = Attributes.builder()
                .put("tool.category", value(category))
                .put("operation.outcome", value(outcome))
                .put("error.type", value(errorType))
                .build();
        toolCallCount.add(1, attributes);
        toolCallDuration.record(Math.max(0L, durationMillis), attributes);
    }

    /** 把输入和输出 Token 分成两个类型写入同一个 Token 计数器。 */
    private void recordTokens(
            long inputTokens,
            long outputTokens) {
        if (tokenUsage == null) return;
        tokenUsage.add(Math.max(0L, inputTokens), Attributes.builder()
                .put("token.type", "input")
                .build());
        tokenUsage.add(Math.max(0L, outputTokens), Attributes.builder()
                .put("token.type", "output")
                .build());
    }

    /** 指标属性为空时统一使用 unknown，避免导出缺少维度的数据。 */
    private static String value(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
