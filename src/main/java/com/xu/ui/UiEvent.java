package com.xu.ui;

import com.xu.hitl.ApprovalResult;
import com.xu.plan.Task;
import com.xu.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Core-to-UI event contract.
 *
 * <p>Agent, Plan, Tool and HITL code only publish immutable events. They never
 * write to the terminal directly. This keeps terminal ownership in one place
 * and makes the same runtime usable from the rich TUI, the plain CLI and
 * tests.</p>
 */
public sealed interface UiEvent permits
        UiEvent.Notice,
        UiEvent.SessionChanged,
        UiEvent.AgentChanged,
        UiEvent.AssistantDelta,
        UiEvent.AssistantCompleted,
        UiEvent.ToolStarted,
        UiEvent.ToolCompleted,
        UiEvent.PlanChanged,
        UiEvent.ApprovalRequested {

    enum Severity {
        INFO, SUCCESS, WARNING, ERROR
    }

    enum SessionAction {
        RESTORED, CLEARED, CONTEXT_INJECTED
    }

    enum AgentPhase {
        STARTED, TURN_STARTED, WAITING_FOR_MODEL, COMPLETED, FAILED, CANCELLED
    }

    enum PlanPhase {
        PLANNING,
        PLAN_CREATED,
        RESUMING,
        EXECUTING,
        ROUND_STARTED,
        TASK_STARTED,
        TASK_COMPLETED,
        REPLANNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    record Notice(Severity severity, String message) implements UiEvent {
        public Notice {
            severity = severity == null ? Severity.INFO : severity;
            message = SafeDisplay.redact(message == null ? "" : message);
        }
    }

    record SessionChanged(
            SessionAction action,
            int messageCount,
            String message) implements UiEvent {
        public SessionChanged {
            action = action == null ? SessionAction.CONTEXT_INJECTED : action;
            message = SafeDisplay.redact(message == null ? "" : message);
        }
    }

    record AgentChanged(
            String taskLabel,
            AgentPhase phase,
            int turn,
            int contextPercent,
            String message) implements UiEvent {
        public AgentChanged {
            taskLabel = normalizeLabel(taskLabel);
            phase = phase == null ? AgentPhase.STARTED : phase;
            contextPercent = Math.max(0, Math.min(100, contextPercent));
            message = SafeDisplay.errorPreview(
                    message == null ? "" : message);
        }
    }

    record AssistantDelta(
            String taskLabel,
            String text) implements UiEvent {
        public AssistantDelta {
            taskLabel = normalizeLabel(taskLabel);
            text = SafeDisplay.redact(text == null ? "" : text);
        }
    }

    record AssistantCompleted(
            String taskLabel,
            String content,
            String outcome,
            int turns,
            int llmCalls,
            int toolCalls,
            int recoveredErrors,
            long inputTokens,
            long outputTokens,
            long durationMillis,
            boolean streamed) implements UiEvent {
        public AssistantCompleted {
            taskLabel = normalizeLabel(taskLabel);
            content = SafeDisplay.redact(content == null ? "" : content);
            outcome = SafeDisplay.text(
                    outcome == null ? "UNKNOWN" : outcome);
        }
    }

    record ToolStarted(
            String taskLabel,
            String callId,
            String toolName,
            Map<String, Object> arguments) implements UiEvent {
        public ToolStarted {
            taskLabel = normalizeLabel(taskLabel);
            callId = SafeDisplay.text(callId == null ? "" : callId);
            toolName = SafeDisplay.text(
                    toolName == null ? "" : toolName);
            arguments = SafeDisplay.arguments(arguments);
        }
    }

    record ToolCompleted(
            String taskLabel,
            String callId,
            String toolName,
            Map<String, Object> arguments,
            ToolExecutionResult result,
            long durationMillis) implements UiEvent {
        public ToolCompleted {
            taskLabel = normalizeLabel(taskLabel);
            callId = SafeDisplay.text(callId == null ? "" : callId);
            toolName = SafeDisplay.text(
                    toolName == null ? "" : toolName);
            arguments = SafeDisplay.arguments(arguments);
            result = result == null
                    ? ToolExecutionResult.failure(
                            "工具没有返回结果", "NULL_RESULT")
                    : new ToolExecutionResult(
                            result.success(),
                            result.success()
                                    ? ""
                                    : SafeDisplay.errorPreview(
                                            result.content()),
                            result.errorType() == null
                                    ? null
                                    : SafeDisplay.text(
                                            result.errorType()),
                            result.exitCode(),
                            result.timedOut());
            durationMillis = Math.max(0L, durationMillis);
        }
    }

    record PlanTaskView(
            String id,
            String description,
            Task.Status status,
            List<String> dependencies) {
        public PlanTaskView {
            id = SafeDisplay.text(id == null ? "" : id);
            description = SafeDisplay.text(
                    description == null ? "" : description);
            status = status == null ? Task.Status.PENDING : status;
            dependencies = dependencies == null
                    ? List.of()
                    : dependencies.stream()
                            .map(SafeDisplay::text)
                            .toList();
        }

        public static PlanTaskView from(Task task) {
            return new PlanTaskView(
                    task.getId(),
                    task.getDescription(),
                    task.getStatus(),
                    task.getDependencies());
        }
    }

    record PlanChanged(
            PlanPhase phase,
            int round,
            int replanCount,
            String taskId,
            String message,
            List<PlanTaskView> tasks) implements UiEvent {
        public PlanChanged {
            phase = phase == null ? PlanPhase.EXECUTING : phase;
            taskId = SafeDisplay.text(taskId == null ? "" : taskId);
            message = SafeDisplay.redact(message == null ? "" : message);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /**
     * A synchronous HITL bridge: the worker publishes this event and waits on
     * {@code response}; the UI thread renders the prompt and completes it.
     */
    record ApprovalRequested(
            long requestId,
            String taskLabel,
            String toolName,
            String dangerLevel,
            Map<String, Object> arguments,
            CompletableFuture<ApprovalResult> response) implements UiEvent {
        public ApprovalRequested {
            taskLabel = normalizeLabel(taskLabel);
            toolName = SafeDisplay.text(
                    toolName == null ? "" : toolName);
            dangerLevel = SafeDisplay.text(
                    dangerLevel == null ? "未知" : dangerLevel);
            arguments = SafeDisplay.arguments(arguments);
            response = response == null ? new CompletableFuture<>() : response;
        }
    }

    private static String normalizeLabel(String label) {
        return label == null || label.isBlank()
                ? "main" : SafeDisplay.text(label);
    }

}
