package com.xu.ui;

import java.io.PrintWriter;
import java.util.stream.Collectors;

/** 用于重定向输入和不受支持终端的轻量无 ANSI 渲染器。 */
public final class PlainUiEventSink implements UiEventSink {

    private final PrintWriter writer;

    public PlainUiEventSink(PrintWriter writer) {
        this.writer = writer;
    }

    @Override
    public synchronized void emit(UiEvent event) {
        if (event instanceof UiEvent.Notice notice) {
            writer.println("[" + notice.severity().name().toLowerCase()
                    + "] " + SafeDisplay.redact(notice.message()));
        } else if (event instanceof UiEvent.SessionChanged session) {
            if (session.action() != UiEvent.SessionAction.CONTEXT_INJECTED) {
                writer.println("[session] " + session.message()
                        + (session.messageCount() > 0
                        ? " (" + session.messageCount() + " 条)" : ""));
            }
        } else if (event instanceof UiEvent.AgentChanged agent
                && "main".equals(agent.taskLabel())
                && (agent.phase() == UiEvent.AgentPhase.CANCELLED
                || (agent.phase() == UiEvent.AgentPhase.FAILED
                && agent.message() != null
                && agent.message().contains("部分工具操作可能已完成")))) {
            writer.println("[" + (agent.phase() == UiEvent.AgentPhase.CANCELLED
                    ? "cancelled" : "warning") + "] " + SafeDisplay.redact(
                    agent.message() == null || agent.message().isBlank()
                            ? "任务已取消" : agent.message()));
        } else if (event instanceof UiEvent.ToolCompleted tool) {
            String status = tool.result().success() ? "OK" : "FAIL";
            String task = "main".equals(tool.taskLabel())
                    ? "" : "[" + tool.taskLabel() + "] ";
            writer.println("  [" + status + "] " + task + tool.toolName()
                    + formatArguments(tool)
                    + " (" + formatDuration(tool.durationMillis()) + ")");
            if (!tool.result().success()
                    && tool.result().content() != null
                    && !tool.result().content().isBlank()) {
                writer.println("         "
                        + SafeDisplay.errorPreview(tool.result().content())
                        .replace("\n", "\n         "));
            }
        } else if (event instanceof UiEvent.PlanChanged plan) {
            switch (plan.phase()) {
                case PLANNING, RESUMING, REPLANNING ->
                        writer.println("[plan] " + plan.message());
                case PLAN_CREATED ->
                        writer.println("[plan] " + plan.message());
                case TASK_STARTED ->
                        writer.println("  [...] " + plan.taskId()
                                + " - " + plan.message());
                case TASK_COMPLETED ->
                        writer.println("  [done] " + plan.taskId()
                                + " - " + plan.message());
                case FAILED -> writer.println("[plan:fail] " + plan.message());
                case CANCELLED ->
                        writer.println("[plan] 计划已取消");
                default -> {
                }
            }
        } else if (event instanceof UiEvent.AssistantCompleted assistant
                && "main".equals(assistant.taskLabel())) {
            writer.println();
            writer.println(SafeDisplay.redact(assistant.content()));
            writer.println();
            writer.println("-- " + assistant.outcome().toLowerCase()
                    + " | " + assistant.turns() + " turns | "
                    + assistant.toolCalls() + " tools | "
                    + formatDuration(assistant.durationMillis()));
        }
        writer.flush();
    }

    private static String formatArguments(UiEvent.ToolCompleted tool) {
        if (tool.arguments().isEmpty()) {
            return "";
        }
        String value = tool.arguments().entrySet().stream()
                .limit(2)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        return "  " + value;
    }

    public static String formatDuration(long millis) {
        if (millis < 1_000) {
            return millis + "ms";
        }
        return String.format("%.1fs", millis / 1_000.0);
    }
}
