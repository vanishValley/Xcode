package com.xu.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

/**
 * Coding Agent 的低基数运行指标。
 *
 * <p>指标刻意排除 Trace 和任务标识，只描述总体运行情况；单次执行通过链路追踪排查。</p>
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

    private AgentMetrics() {
        taskCount = null;
        taskDuration = null;
        llmCallCount = null;
        llmCallDuration = null;
        tokenUsage = null;
        toolCallCount = null;
        toolCallDuration = null;
    }

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

    static AgentMetrics noop() {
        return NOOP;
    }

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

    private static String value(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
