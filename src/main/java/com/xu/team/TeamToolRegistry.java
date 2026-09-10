package com.xu.team;

import com.xu.agent.AgentRunHooks;
import com.xu.llm.LlmClient.Message;
import com.xu.llm.LlmClient.ToolCall;
import com.xu.tool.*;
import com.xu.util.CancellationToken;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static com.xu.team.TeamTypes.*;

/** 每成员独立的工具视图。Schema 与 execute 双重限制，底层审批包装不被绕过。 */
final class TeamToolRegistry extends ToolRegistry {
    private static final Set<String> READ_ONLY = Set.of(
            "read_file", "list_dir", "glob_files", "web_search", "web_fetch", "load_skill");
    private final ToolRegistry base;
    private final TeamRuntime runtime;
    private final boolean lead;
    private final String agentId;
    private final Path root;
    private final CancellationToken token;
    private final ThreadLocal<String> requestId = new ThreadLocal<>();
    private final ThreadLocal<String> callId = new ThreadLocal<>();

    TeamToolRegistry(ToolRegistry base, TeamRuntime runtime, String agentId,
                     boolean lead, Path root, CancellationToken token) throws java.io.IOException {
        this.base = base; this.runtime = runtime; this.agentId = agentId; this.lead = lead;
        this.root = root.toRealPath(); this.token = token;
        installTools();
    }

    private void installTools() {
        if (lead) {
            add("spawn_agent", "异步创建只读调查成员，返回 ID；不能给成员修改或 Shell 任务。",
                    schema(Map.of("task", str(), "context", str(), "inputRefs", strings(),
                            "acceptanceCriteria", strings(), "expectedOutput", str(), "profile", str()), "task"), args -> {
                        if (!"READ_ONLY".equals(string(args, "profile", "READ_ONLY")))
                            throw new TeamException("CAPABILITY_DENIED", "第一版只支持 READ_ONLY 成员");
                        return runtime.spawn(operationKey(), new Task(string(args, "task", ""),
                                string(args, "context", ""), list(args, "inputRefs"),
                                list(args, "acceptanceCriteria"), string(args, "expectedOutput", "")));
                    });
            add("send_message", "向成员发送 NOTIFY（当前轮补充）、CONTINUE（下一轮任务）或 ANSWER（回复问题）。",
                    schema(Map.of("targetId", str(), "action", str(), "content", str(), "replyTo", str()),
                            "targetId", "action", "content"), args -> {
                        Action action;
                        try { action = Action.valueOf(string(args, "action", "")); }
                        catch (IllegalArgumentException e) { throw new TeamException("INVALID_ARGUMENT", "action 必须为 NOTIFY/CONTINUE/ANSWER"); }
                        return runtime.send(operationKey(), string(args, "targetId", ""), action,
                                string(args, "content", ""), string(args, "replyTo", ""));
                    });
            add("list_agents", "查看当前团队成员状态和待处理消息数量。", schema(Map.of()), args -> runtime.snapshots());
            add("wait_agents", "等待任何新事件（问题/失败也唤醒）；超时不是失败，不重复轮询。",
                    schema(Map.of("afterSequence", integer(), "maxEvents", integer(), "timeoutMs", integer())), args ->
                            runtime.await(number(args, "afterSequence", 0), number(args, "maxEvents", 16),
                                    Duration.ofMillis(number(args, "timeoutMs", 30000))));
            add("get_agent_result", "按 resultId 读取完整结果，可使用 offset 分段读取。",
                    schema(Map.of("resultId", str(), "offset", integer()), "resultId"), args -> {
                        Result result = runtime.result(string(args, "resultId", ""));
                        String content = result.content() == null ? "" : result.content();
                        int offset = number(args, "offset", 0);
                        if (offset < 0 || offset > content.length()) throw new TeamException("INVALID_ARGUMENT", "offset 越界");
                        int end = Math.min(content.length(), offset + 12000);
                        return Map.of("resultId", result.resultId(), "agentId", result.agentId(),
                                "runId", result.runId(), "outcome", result.outcome(), "content", content.substring(offset, end),
                                "nextOffset", end, "complete", end == content.length());
                    });
            add("stop_agent", "请求停止成员；CANCELLING 不表示已退出。",
                    schema(Map.of("agentId", str(), "reason", str()), "agentId"), args -> {
                        runtime.stop(string(args, "agentId", ""), string(args, "reason", "主 Agent 请求停止"));
                        return runtime.snapshots();
                    });
            add("resolve_question", "明确取消或解除不再适用的问题，必须说明原因；不能假装用户已回答。",
                    schema(Map.of("questionId", str(), "reason", str()), "questionId", "reason"), args -> {
                        runtime.resolveQuestion(string(args, "questionId", ""), string(args, "reason", ""));
                        return Map.of("resolved", true);
                    });
        } else {
            add("report_to_parent", "报告 PROGRESS，或 QUESTION + needsReply=true 请求暂停等待主 Agent。",
                    schema(Map.of("kind", str(), "content", str(), "needsReply", Map.of("type", "boolean")), "kind", "content"), args -> {
                        Object needsReply = args.getOrDefault("needsReply", false);
                        if (!(needsReply instanceof Boolean)) throw new TeamException("INVALID_ARGUMENT", "needsReply 必须为布尔值");
                        return Map.of("messageId", runtime.report(agentId, operationKey(),
                                string(args, "kind", ""), string(args, "content", ""), (Boolean) needsReply));
                    });
        }
    }

    AgentRunHooks bind(AgentRunHooks hooks) {
        return new AgentRunHooks() {
            @Override public int maxTurns() { return hooks.maxTurns(); }
            @Override public String instructions() { return hooks.instructions(); }
            @Override public List<Message> receive() { return hooks.receive(); }
            @Override public void beforeRequest() { requestId.set(UUID.randomUUID().toString()); hooks.beforeRequest(); }
            @Override public void beforeTool(ToolCall call) { callId.set(call.id); hooks.beforeTool(call); }
            @Override public boolean pauseRequested() { return hooks.pauseRequested(); }
            @Override public String pauseReason() { return hooks.pauseReason(); }
            @Override public String finishBlocker() { return hooks.finishBlocker(); }
            @Override public boolean preserveHistoryOnFailure() { return true; }
        };
    }

    private String operationKey() {
        if (requestId.get() == null || callId.get() == null) throw new TeamException("INVALID_CALL", "缺少运行时工具调用身份");
        return agentId + ":" + requestId.get() + ":" + callId.get();
    }

    void clearRun() { requestId.remove(); callId.remove(); }

    @Override public synchronized Set<String> names() {
        Set<String> names = new LinkedHashSet<>(super.names());
        for (String name : base.names()) if (lead || READ_ONLY.contains(name)) names.add(name);
        return names;
    }
    @Override public boolean isEmpty() { return names().isEmpty(); }
    @Override public List<Map<String, Object>> toOpenAiTools() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : names()) {
            Tool tool = get(name);
            if (tool != null) definitions.add(Map.of("type", "function", "function", Map.of(
                    "name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema())));
        }
        return definitions;
    }

    @Override public Tool get(String name) {
        Tool local = super.get(name);
        if (local != null) return local;
        if (!lead && !READ_ONLY.contains(name)) return null;
        // 保留原有 HITL 包装；未知能力默认归入主 Agent 独占阶段。
        Tool original = base.get(name);
        if (original == null) return null;
        return new Tool() {
            @Override public String name() { return original.name(); }
            @Override public String description() { return original.description(); }
            @Override public Map<String, Object> inputSchema() { return original.inputSchema(); }
            @Override public String execute(Map<String, Object> args) throws Exception { return executeObserved(args).content(); }
            @Override public ToolExecutionResult executeObserved(Map<String, Object> args) throws Exception {
                token.throwIfCancellationRequested();
                if (!token.isReusable()) return ToolExecutionResult.failure("上次操作未安全结束", "SIDE_EFFECT_UNKNOWN");
                try {
                    Map<String, Object> scoped = scopedArguments(name, args);
                    if (READ_ONLY.contains(name)) return original.executeObserved(scoped);
                    try (AutoCloseable lease = runtime.exclusive()) { return original.executeObserved(scoped); }
                } catch (TeamException e) { return ToolExecutionResult.failure(e.getMessage(), e.code()); }
            }
        };
    }

    private Map<String, Object> scopedArguments(String name, Map<String, Object> args) throws java.io.IOException {
        if (!Set.of("read_file", "list_dir").contains(name)) return args;
        Path requested = Path.of(string(args, "path", name.equals("list_dir") ? "." : ""));
        Path resolved = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize().toRealPath();
        // realPath 防止通过 .. / 符号链接读取工作区外内容。
        if (!resolved.startsWith(root)) throw new TeamException("CAPABILITY_DENIED", "Team 文件读取仅限当前工作区");
        Map<String, Object> scoped = new HashMap<>(args);
        scoped.put("path", resolved.toString());
        return scoped;
    }

    private void add(String name, String description, Map<String, Object> schema, Operation operation) {
        register(new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String execute(Map<String, Object> args) throws Exception { return executeObserved(args).content(); }
            @Override public ToolExecutionResult executeObserved(Map<String, Object> args) throws Exception {
                token.throwIfCancellationRequested();
                try { return ToolExecutionResult.success(TeamJson.write(operation.run(args))); }
                catch (TeamException e) { return ToolExecutionResult.failure(e.getMessage(), e.code()); }
            }
        });
    }
    private interface Operation { Object run(Map<String, Object> args) throws Exception; }
    private static Map<String, Object> str() { return Map.of("type", "string"); }
    private static Map<String, Object> integer() { return Map.of("type", "integer"); }
    private static Map<String, Object> strings() { return Map.of("type", "array", "items", str()); }
    private static Map<String, Object> schema(Map<String, Object> props, String... required) {
        return Map.of("type", "object", "properties", props, "required", List.of(required), "additionalProperties", false);
    }
    private static String string(Map<String, Object> args, String key, String fallback) {
        Object value = args.getOrDefault(key, fallback);
        if (!(value instanceof String text)) throw new TeamException("INVALID_ARGUMENT", key + " 必须为字符串");
        return text;
    }
    private static int number(Map<String, Object> args, String key, int fallback) {
        Object value = args.getOrDefault(key, fallback);
        if (!(value instanceof Integer n)) throw new TeamException("INVALID_ARGUMENT", key + " 必须为整数");
        return n;
    }
    private static List<String> list(Map<String, Object> args, String key) {
        Object value = args.getOrDefault(key, List.of());
        if (!(value instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String)))
            throw new TeamException("INVALID_ARGUMENT", key + " 必须为字符串数组");
        return values.stream().map(String.class::cast).toList();
    }
}
