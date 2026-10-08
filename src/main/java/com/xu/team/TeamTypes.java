package com.xu.team;

import java.util.List;

public final class TeamTypes {
    private TeamTypes() {}
    public enum State { CREATING, QUEUED, RUNNING, IDLE, WAITING_INPUT, CANCELLING, CANCELLED, FAILED, CLOSED }
    public enum Action { NOTIFY, CONTINUE, ANSWER }
    public enum Profile { READ_ONLY, ISOLATED_WRITE }
    public record Task(String task, String context, List<String> inputRefs,
                       List<String> acceptanceCriteria, String expectedOutput,
                       Profile profile, List<String> dependsOn) {
        public Task(String task, String context, List<String> inputRefs,
                    List<String> acceptanceCriteria, String expectedOutput) {
            this(task, context, inputRefs, acceptanceCriteria, expectedOutput, Profile.READ_ONLY, List.of());
        }
        public Task {
            task = text(task, "task", 8192);
            context = optional(context, 8192);
            expectedOutput = optional(expectedOutput, 4096);
            inputRefs = copy(inputRefs);
            acceptanceCriteria = copy(acceptanceCriteria);
            profile = java.util.Objects.requireNonNull(profile);
            dependsOn = copy(dependsOn);
            if (profile == Profile.READ_ONLY && !dependsOn.isEmpty())
                throw new TeamException("INVALID_ARGUMENT", "代码交付依赖仅支持 ISOLATED_WRITE；只读调查请通过 context/inputRefs 传递背景");
            if (new java.util.HashSet<>(dependsOn).size() != dependsOn.size())
                throw new TeamException("INVALID_ARGUMENT", "dependsOn 不能重复");
        }
        private static List<String> copy(List<String> values) {
            List<String> copy = values == null ? List.of() : List.copyOf(values);
            if (copy.size() > 32) throw new TeamException("INVALID_ARGUMENT", "列表最多 32 项");
            copy.forEach(v -> text(v, "list item", 2048));
            return copy;
        }
    }
    public record Receipt(String agentId, String runId, State state) {}
    public record Delivery(String messageId, long sequence) {}
    public record Envelope(String messageId, long sequence, String senderId,
                           Action action, String replyTo, String content) {}
    public record Event(long sequence, String type, String agentId, String runId,
                        String messageId, String content, String resultId) {}
    public record Batch(List<Event> events, long nextSequence, boolean timedOut) {
        public Batch { events = List.copyOf(events); }
    }
    public record Snapshot(String agentId, String runId, State state, String task,
                           int pendingMessages, long workspaceEpoch, String questionId,
                           String question, String lastResultId) {}
    public record Result(String resultId, String agentId, String runId, String outcome,
                         String content, long inputTokens, long outputTokens) {}
    public record Question(String id, String agentId, String content, boolean resolved) {}
    public record Contract(String messageId, String targetId, String content, String status, String response) {}

    public static String text(String value, String name, int bytes) {
        if (value == null || value.isBlank()) throw new TeamException("INVALID_ARGUMENT", name + " 不能为空");
        return optional(value, bytes);
    }
    public static String optional(String value, int bytes) {
        String text = value == null ? "" : value;
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > bytes)
            throw new TeamException("INVALID_ARGUMENT", "正文过长，请使用成果引用");
        return text;
    }
}
