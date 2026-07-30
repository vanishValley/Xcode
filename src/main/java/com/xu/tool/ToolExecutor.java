package com.xu.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.llm.LlmClient.ToolCall;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 统一执行模型返回的 ToolCall。
 *
 * <p>Agent 和 Reviewer 都通过该组件执行工具，保证参数解析、错误语义、
 * Span 属性和结构化日志保持一致。</p>
 */
public final class ToolExecutor {

    private static final Logger logger =
            LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final Tracing tracing;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolExecutor(ToolRegistry registry, Tracing tracing) {
        this.registry = registry;
        this.tracing = tracing;
    }

    /**
     * 解析并执行一次模型返回的工具调用。
     *
     * <p>工具不存在、参数解析失败或工具自身异常都会转换成失败结果回灌给
     * LLM，而不是直接终止 ReAct 循环；同时对应的 {@code tool.execute}
     * Span 会被标记为失败。</p>
     *
     * @param call 模型返回的工具名、调用 ID 和 JSON 参数
     * @return 可直接写回对话历史的统一执行结果
     */
    public ToolExecutionResult execute(ToolCall call) {
        String toolName = call != null && call.function != null
                ? call.function.name : "";
        String arguments = call != null && call.function != null
                ? call.function.arguments : "";
        String callId = call == null ? null : call.id;

        // 不记录参数正文，只记录长度，避免命令、源码或敏感数据进入日志。
        try (TraceScope scope = tracing.start("tool.execute")
                .attribute("tool.name", toolName)
                .attribute("tool.call_id", callId)
                .attribute("tool.category",
                        toolName.startsWith("mcp__") ? "mcp" : "local")
                .attribute("tool.arguments_chars",
                        arguments == null ? 0L : arguments.length())) {

            Tool tool = registry.get(toolName);
            if (tool == null) {
                String message = "工具不存在：" + toolName;
                scope.error("TOOL_NOT_FOUND", message);
                logException(
                        toolName,
                        callId,
                        "TOOL_NOT_FOUND",
                        scope.elapsedMillis(),
                        null);
                return ToolExecutionResult.failure(
                        message, "TOOL_NOT_FOUND");
            }

            try {
                Map<String, Object> parsedArguments =
                        arguments == null || arguments.isBlank()
                                ? Map.of()
                                : objectMapper.readValue(
                                        arguments,
                                        new TypeReference<Map<String, Object>>() {});
                ToolExecutionResult execution =
                        tool.executeObserved(parsedArguments);
                if (execution == null) {
                    throw new IllegalStateException(
                            "工具返回了 null 执行结果");
                }

                String content = execution.content() == null
                        ? "" : execution.content();
                String normalizedErrorType = execution.success()
                        ? null
                        : execution.errorType() == null
                                ? "TOOL_REPORTED_FAILURE"
                                : execution.errorType();
                ToolExecutionResult normalized = new ToolExecutionResult(
                        execution.success(),
                        content,
                        normalizedErrorType,
                        execution.exitCode(),
                        execution.timedOut());

                scope.attribute("tool.result_chars", content.length())
                        .attribute(
                                "tool.status",
                                normalized.success()
                                        ? "SUCCESS" : "FAILED")
                        .attribute("tool.timed_out", normalized.timedOut());
                if (normalized.exitCode() != null) {
                    scope.attribute(
                            "process.exit_code",
                            normalized.exitCode().longValue());
                }

                if (normalized.success()) {
                    var event = logger.atDebug()
                            .addKeyValue(
                                    "event", "tool.execute.completed")
                            .addKeyValue("tool_name", toolName)
                            .addKeyValue("tool_call_id", callId)
                            .addKeyValue(
                                    "result_chars", content.length())
                            .addKeyValue(
                                    "duration_ms", scope.elapsedMillis());
                    addProcessFields(event, normalized);
                    event.log("工具执行完成");
                    return normalized;
                }

                String errorType = normalized.errorType();
                scope.error(errorType, errorType);
                logReportedFailure(
                        toolName,
                        callId,
                        errorType,
                        normalized,
                        scope.elapsedMillis());
                return normalized;
            } catch (Exception error) {
                scope.fail(error);
                scope.attribute("tool.status", "ERROR");
                String errorType = error.getClass().getSimpleName();
                logException(
                        toolName,
                        callId,
                        errorType,
                        scope.elapsedMillis(),
                        error);
                return ToolExecutionResult.failure(
                        "工具执行出错：" + safeMessage(error),
                        errorType);
            }
        }
    }

    private static void logReportedFailure(
            String toolName,
            String callId,
            String errorType,
            ToolExecutionResult result,
            long durationMillis) {
        var event = result.timedOut()
                ? logger.atError() : logger.atWarn();
        event.addKeyValue("event", "tool.execute.failed")
                .addKeyValue("tool_name", toolName)
                .addKeyValue("tool_call_id", callId)
                .addKeyValue("error_type", errorType)
                .addKeyValue(
                        "result_chars",
                        result.content() == null
                                ? 0 : result.content().length())
                .addKeyValue("duration_ms", durationMillis);
        addProcessFields(event, result);
        event.log("工具报告执行失败");
    }

    private static void logException(
            String toolName,
            String callId,
            String errorType,
            long durationMillis,
            Throwable error) {
        var event = logger.atError()
                .addKeyValue("event", "tool.execute.failed")
                .addKeyValue("tool_name", toolName)
                .addKeyValue("tool_call_id", callId)
                .addKeyValue("error_type", errorType)
                .addKeyValue("duration_ms", durationMillis);
        if (error != null) event.setCause(error);
        event.log("工具执行失败");
    }

    private static void addProcessFields(
            org.slf4j.spi.LoggingEventBuilder event,
            ToolExecutionResult result) {
        if (result.exitCode() != null) {
            event.addKeyValue("exit_code", result.exitCode());
        }
        event.addKeyValue("timed_out", result.timedOut());
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
