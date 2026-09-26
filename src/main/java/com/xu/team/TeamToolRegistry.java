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
    private final TeamWorkspaceService workflow;
    private final boolean writer;
    private final ThreadLocal<String> requestId = new ThreadLocal<>();
    private final ThreadLocal<String> callId = new ThreadLocal<>();

    TeamToolRegistry(ToolRegistry base, TeamRuntime runtime, String agentId,
                     boolean lead, Path root, CancellationToken token) throws java.io.IOException {
        this(base, runtime, agentId, lead, root, token, null, false);
    }

    TeamToolRegistry(ToolRegistry base, TeamRuntime runtime, String agentId,
                     boolean lead, Path root, CancellationToken token,
                     TeamWorkspaceService workflow, boolean writer) throws java.io.IOException {
        this.base = base; this.runtime = runtime; this.agentId = agentId; this.lead = lead;
        this.root = root.toRealPath(); this.token = token;
        this.workflow = workflow; this.writer = writer;
        installTools();
    }

    private void installTools() {
        if (lead) {
            add("spawn_agent", "创建 READ_ONLY 调查成员或 ISOLATED_WRITE 独立写成员；依赖必须先交付并停止。",
                    schema(Map.of("task", str(), "context", str(), "inputRefs", strings(),
                            "acceptanceCriteria", strings(), "expectedOutput", str(), "profile", str(), "dependsOn", strings()), "task"), args -> {
                        Profile profile;
                        try { profile = Profile.valueOf(string(args, "profile", "READ_ONLY")); }
                        catch (IllegalArgumentException e) { throw new TeamException("INVALID_ARGUMENT", "未知 profile"); }
                        List<String> dependencies = list(args, "dependsOn");
                        if ((profile == Profile.ISOLATED_WRITE || !dependencies.isEmpty()) && workflow == null)
                            throw new TeamException("CAPABILITY_DENIED", "未配置独立工作区服务");
                        try (AutoCloseable ignored = runtime.leaseMembers(dependencies)) {
                            if (workflow != null) workflow.checkDependencies(dependencies);
                            return runtime.spawn(operationKey(), new Task(string(args, "task", ""),
                                string(args, "context", ""), list(args, "inputRefs"),
                                list(args, "acceptanceCriteria"), string(args, "expectedOutput", ""), profile, dependencies));
                        }
                    });
            add("send_contract_change", "投递需要明确确认的公共契约变更；空闲成员需要 CONTINUE 唤醒。",
                    schema(Map.of("targetId", str(), "content", str()), "targetId", "content"), args ->
                            runtime.sendContract(operationKey(), string(args, "targetId", ""), string(args, "content", "")));
            add("list_contracts", "查看契约消息的投递、呈现及确认状态。", schema(Map.of()), args -> runtime.contracts());
            add("resolve_contract", "撤回不再适用或被拒绝的契约，必须说明原因；不会伪造接收方确认。",
                    schema(Map.of("messageId", str(), "reason", str()), "messageId", "reason"), args -> {
                        runtime.resolveContract(string(args, "messageId", ""), string(args, "reason", ""));
                        return runtime.contracts();
                    });
            if (workflow != null) installIntegrationTools();
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
            add("acknowledge_contract", "明确接受或拒绝已呈现的契约消息，确认不等于代码已适配。",
                    schema(Map.of("messageId", str(), "accepted", Map.of("type", "boolean"), "response", str()),
                            "messageId", "accepted", "response"), args -> {
                        if (!(args.get("accepted") instanceof Boolean accepted))
                            throw new TeamException("INVALID_ARGUMENT", "accepted 必须是布尔值");
                        return runtime.acknowledge(agentId, string(args, "messageId", ""), accepted, string(args, "response", ""));
                    });
            if (writer) add("submit_changes", "停止后台写入，提交本工作区全部改动并冻结成果；之后修改须重新交付。",
                    schema(Map.of("summary", str()), "summary"), args -> {
                        String blocker = runtime.contractBlocker(agentId);
                        if (!blocker.isEmpty()) throw new TeamException("CONTRACT_PENDING", blocker);
                        return workflow.submit(agentId, string(args, "summary", ""));
                    });
            add("report_to_parent", "报告 PROGRESS，或 QUESTION + needsReply=true 请求暂停等待主 Agent。",
                    schema(Map.of("kind", str(), "content", str(), "needsReply", Map.of("type", "boolean")), "kind", "content"), args -> {
                        Object needsReply = args.getOrDefault("needsReply", false);
                        if (!(needsReply instanceof Boolean)) throw new TeamException("INVALID_ARGUMENT", "needsReply 必须为布尔值");
                        return Map.of("messageId", runtime.report(agentId, operationKey(),
                                string(args, "kind", ""), string(args, "content", ""), (Boolean) needsReply));
                    });
        }
    }

    private void installIntegrationTools() {
        add("list_workspaces", "查看工作区、固定交付、集成候选和验证证据。", schema(Map.of()), args -> workflow.snapshot());
        add("build_integration", "在独立集成分支组合已停止成员的固定交付，冲突保留现场；不改用户分支。",
                schema(Map.of("agentIds", strings()), "agentIds"), args -> {
                    List<String> ids = list(args, "agentIds");
                    try (AutoCloseable ignored = runtime.leaseMembers(ids)) { return workflow.build(ids); }
                });
        add("integration_command", "在候选目录执行检查/修复命令，保留审批；每次调用撤销旧验证，修复后需提交再验证。",
                schema(Map.of("candidateId", str(), "command", str()), "candidateId", "command"), args -> {
                    String id = string(args, "candidateId", "");
                    var candidate = workflow.candidate(id);
                    workflow.candidateChanging(id);
                    return base.forWorkspace(Path.of(candidate.root()), workflow.candidateEnvironment(id), token)
                            .get("execute_command").executeObserved(Map.of("command", string(args, "command", "")));
                });
        add("verify_integration", "执行团队启动时捕获的项目检查策略，验证绑定候选提交；不能指定替代命令。",
                schema(Map.of("candidateId", str()), "candidateId"), args ->
                        workflow.verify(string(args, "candidateId", ""), base, token));
        add("review_integration", "记录 Agent 审查和证据；要求检查通过且输入/目标未变化，不等于人工批准或已合入。",
                schema(Map.of("candidateId", str(), "evidence", str()), "candidateId", "evidence"), args -> {
                    String id = string(args, "candidateId", "");
                    List<String> ids = workflow.candidate(id).inputs().stream().map(TeamWorkspaceService.Change::agentId).toList();
                    try (AutoCloseable ignored = runtime.leaseMembers(ids)) {
                        String blocker = runtime.contractBlocker(null);
                        if (!blocker.isEmpty()) throw new TeamException("CONTRACT_PENDING", blocker);
                        return workflow.review(id, string(args, "evidence", ""));
                    }
                });
        add("defer_changes", "保留未交付成果并说明原因；团队报告将标记部分完成。成员必须已退出本轮。",
                schema(Map.of("agentId", str(), "reason", str()), "agentId", "reason"), args -> {
                    String id = string(args, "agentId", "");
                    try (AutoCloseable ignored = runtime.leaseMembers(List.of(id))) {
                        workflow.defer(id, string(args, "reason", ""));
                        return workflow.snapshot();
                    }
                });
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
            @Override public String finishBlocker() {
                if (workflow != null) {
                    String blocker;
                    if (lead) {
                        try (AutoCloseable ignored = runtime.leaseMembers(runtime.snapshots().stream().map(Snapshot::agentId).toList())) {
                            blocker = workflow.finishBlocker();
                            if (!blocker.isEmpty()) return blocker;
                            return hooks.finishBlocker();
                        } catch (Exception e) { return e.getMessage(); }
                    }
                    blocker = writer ? workflow.workerBlocker(agentId) : "";
                    if (!blocker.isEmpty()) return blocker;
                }
                String blocker = runtime.contractBlocker(lead ? null : agentId);
                return blocker.isEmpty() ? hooks.finishBlocker() : blocker;
            }
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
        for (String name : base.names()) if (allowed(name)) names.add(name);
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
        if (!allowed(name)) return null;
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
                    if (writer) {
                        workflow.invalidate(agentId);
                        return original.executeObserved(scoped);
                    }
                    try (AutoCloseable lease = runtime.exclusive()) { return original.executeObserved(scoped); }
                } catch (TeamException e) { return ToolExecutionResult.failure(e.getMessage(), e.code()); }
            }
        };
    }

    private boolean allowed(String name) {
        return lead || READ_ONLY.contains(name) || writer && Set.of("write_file", "execute_command").contains(name);
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
                if (!token.isReusable()) return ToolExecutionResult.failure("上次操作未安全结束", "SIDE_EFFECT_UNKNOWN");
                try {
                    Object result = operation.run(args);
                    return result instanceof ToolExecutionResult execution ? execution : ToolExecutionResult.success(TeamJson.write(result));
                }
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
