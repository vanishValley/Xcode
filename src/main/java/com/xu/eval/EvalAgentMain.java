package com.xu.eval;

import com.xu.agent.Agent;
import com.xu.agent.AgentRunHooks;
import com.xu.agent.PlanExecuteAgent;
import com.xu.agent.Reviewer;
import com.xu.llm.LlmClient;
import com.xu.memory.MemoryManager;
import com.xu.observability.ExecutionCapture;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.plan.PlanStore;
import com.xu.team.TeamCoordinator;
import com.xu.tool.Tool;
import com.xu.tool.ToolExecutionResult;
import com.xu.tool.ToolRegistry;
import com.xu.tool.impl.*;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 评测装配已有 Agent 与统一可观测模块；不维护第二套日志/用量采集器。 */
public final class EvalAgentMain {
    public record Request(String prompt, String adapter, String profile, String model,
                          int maxCalls, long maxTokens, int maxTools, int maxTurns,
                          String fault, Map<String, String> script) {}
    public record Result(String runtimeStatus, long inputTokens, long outputTokens,
                         boolean usageComplete, int llmCalls, int toolCalls,
                         boolean faultTriggered, String error) {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("xcode.log.dir", output.resolve("logs").toString());
        Request request = EvalFiles.JSON.readValue(System.in, Request.class);
        EvalFiles.json(output.resolve("worker.json"), execute(request, output));
        System.exit(0);
    }

    static Result execute(Request request, Path output) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String key = System.getenv("DEEPSEEK_API_KEY");
        if ("live".equals(request.adapter()) && (key == null || key.isBlank()))
            throw new IllegalArgumentException("Live eval requires DEEPSEEK_API_KEY in environment");
        CancellationToken cancellation = new CancellationToken();
        cancellation.beginRun();
        try (Tracing tracing = Tracing.forEvaluation(output.resolve("observability"))) {
            ExecutionCapture capture = tracing.capture(request.profile(), request.prompt());
            writeObservation(output, capture);
            LlmClient backend = "scripted".equals(request.adapter())
                    ? new ScriptClient(request.script(), tracing) : new LlmClient(key, request.model(), tracing);
            BudgetedClient client = new BudgetedClient(backend, request, capture, cancellation);
            ToolRegistry registry = new ToolRegistry();
            AtomicBoolean injected = new AtomicBoolean();
            for (Tool tool : List.of(new ReadFileTool(), new WriteFileTool(root),
                    new ListDirTool(), new GlobFilesTool(), new ExecuteCommandTool())) {
                registry.register(withFault(tool, request.fault(), injected, capture));
            }
            String status = "SUCCESS", error = "";
            try {
                UiEventSink sink = event -> capture.event("ui", event);
                if ("plan".equals(request.profile())) {
                    String answer = new PlanExecuteAgent(client, registry, new PlanStore(output),
                            null, root.toString(), null, tracing, sink, cancellation).execute(request.prompt());
                    capture.event("answer", answer);
                } else if ("team".equals(request.profile())) {
                    String answer = new TeamCoordinator(client, registry, null, null, root, output,
                            tracing, sink, cancellation).execute(request.prompt());
                    capture.event("answer", answer);
                } else {
                    MemoryManager memory = new MemoryManager(null, null, client, root.toString());
                    if (!"no-goal".equals(request.profile())) memory.setGoal(request.prompt());
                    Agent agent = new Agent(client, registry, memory, null, "eval", tracing, sink, cancellation);
                    AgentRunHooks hooks = new AgentRunHooks() {
                        @Override public int maxTurns() { return request.maxTurns(); }
                    };
                    var run = agent.runCoordinated(request.prompt(), hooks);
                    status = run.outcome();
                    capture.event("answer", run.content());
                    if ("review".equals(request.profile()) && "SUCCESS".equals(status)) {
                        var review = new Reviewer(client, tracing, cancellation).review(request.prompt(), request.prompt(), run.content());
                        capture.event("review", review);
                        if (!review.approved()) {
                            run = agent.runCoordinated("根据审查反馈修复：" + review.issues()
                                    + "\n建议：" + review.suggestions(), hooks);
                            status = run.outcome();
                            capture.event("answer", run.content());
                        }
                    }
                }
            } catch (Exception exception) {
                status = client.budgetExceeded ? "BUDGET_EXCEEDED" : "AGENT_ERROR";
                error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                capture.event("error", error);
            } finally {
                backend.cancelActiveRequests();
                var usage = capture.snapshot();
                if (client.budgetExceeded || usage.inputTokens() + usage.outputTokens() > request.maxTokens())
                    status = "BUDGET_EXCEEDED";
                capture.outcome(status);
                capture.close();
                writeObservation(output, capture);
            }
            var snapshot = capture.snapshot();
            return new Result(status, snapshot.inputTokens(), snapshot.outputTokens(), snapshot.usageComplete(),
                    snapshot.llmCalls(), snapshot.requestedTools(), snapshot.faultTriggered(), error);
        }
    }

    private static void writeObservation(Path output, ExecutionCapture capture) throws IOException {
        EvalFiles.json(output.resolve("observation.json"), Map.of("traceId", capture.traceId(),
                "directory", output.relativize(capture.directory()).toString().replace('\\', '/')));
    }

    /** 装饰器只制造测试故障，工具开始/结束与正文由原有 ToolExecutor 采集。 */
    private static Tool withFault(Tool delegate, String fault, AtomicBoolean injected, ExecutionCapture capture) {
        return new Tool() {
            @Override public String name() { return delegate.name(); }
            @Override public String description() { return delegate.description(); }
            @Override public Map<String, Object> inputSchema() { return delegate.inputSchema(); }
            @Override public String execute(Map<String, Object> args) throws Exception { return executeObserved(args).content(); }
            @Override public ToolExecutionResult executeObserved(Map<String, Object> args) throws Exception {
                boolean matches = (fault.equals("read-once") && name().equals("read_file"))
                        || (fault.equals("write-once") && name().equals("write_file"));
                if (matches && injected.compareAndSet(false, true)) {
                    capture.event("fault", Map.of("name", name(), "type", fault));
                    return ToolExecutionResult.failure("临时 I/O 故障，本次没有执行，可重新调用。", "INJECTED_IO");
                }
                return delegate.executeObserved(args);
            }
        };
    }

    /** 只负责准入预算。tokens、工具次数与报告都读取统一 Capture，不重复累加。 */
    static final class BudgetedClient extends LlmClient {
        private final LlmClient delegate;
        private final Request request;
        private final ExecutionCapture capture;
        private final CancellationToken cancellation;
        private int admittedCalls;
        volatile boolean budgetExceeded;
        BudgetedClient(LlmClient delegate, Request request, ExecutionCapture capture, CancellationToken cancellation) {
            super("", "eval-budget");
            this.delegate = delegate; this.request = request; this.capture = capture; this.cancellation = cancellation;
        }
        @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools) throws IOException {
            return invoke(messages, tools, 4096);
        }
        @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools, int maxOutput) throws IOException {
            return invoke(messages, tools, maxOutput);
        }
        @Override public Message chatRawStreaming(List<Message> messages, List<Map<String, Object>> tools,
                                                  Consumer<String> onTextDelta) throws IOException {
            Message reply = invoke(messages, tools, 4096);
            if (onTextDelta != null && reply.content != null) onTextDelta.accept(reply.content);
            return reply;
        }
        private Message invoke(List<Message> messages, List<Map<String, Object>> definitions, int maxOutput) throws IOException {
            synchronized (this) {
                var usage = capture.snapshot();
                if (admittedCalls >= request.maxCalls() || usage.inputTokens() + usage.outputTokens() >= request.maxTokens()
                        || usage.requestedTools() >= request.maxTools()) {
                    budgetExceeded = true;
                    cancellation.cancel();
                    throw new IOException("Evaluation budget exhausted");
                }
                // 并发请求先占名额；这只是调度配额，不充当观测调用次数。
                admittedCalls++;
            }
            Message reply = delegate.chatRaw(messages, definitions, maxOutput);
            if (capture.snapshot().requestedTools() > request.maxTools()) {
                budgetExceeded = true;
                cancellation.cancel();
            }
            return reply;
        }
        @Override public void cancelActiveRequests() { delegate.cancelActiveRequests(); }
    }

    /** 假模型也发出与真实 LlmClient 相同的 Span，测试真实采集链路而非另造评测事件。 */
    private static final class ScriptClient extends LlmClient {
        private final List<Map.Entry<String, String>> edits;
        private final AtomicInteger next = new AtomicInteger();
        private final Tracing tracing;
        ScriptClient(Map<String, String> edits, Tracing tracing) {
            super("", "scripted", tracing); this.edits = List.copyOf(edits.entrySet()); this.tracing = tracing;
        }
        @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools) throws IOException {
            try (TraceScope scope = tracing.startClient("llm.chat").attribute("gen_ai.request.model", "SCRIPTED_NO_MODEL");
                 var artifact = tracing.artifacts().beginOperation("llm", "scripted", EvalFiles.JSON.writeValueAsString(messages))) {
                Message last = messages.get(messages.size() - 1);
                if ("tool".equals(last.role) && last.content != null && last.content.contains("本次没有执行")) next.decrementAndGet();
                int index = next.getAndIncrement();
                Message message = new Message("assistant", null);
                if (index == 0) {
                    message.toolCalls = List.of(call("read_file", Map.of("path", edits.get(0).getKey()), index));
                } else if (index <= edits.size()) {
                    var edit = edits.get(index - 1);
                    message.toolCalls = List.of(call("write_file", Map.of("path", edit.getKey(), "content", edit.getValue()), index));
                } else message.content = "脚本化 fixture 修改已完成，交给独立裁判。";
                scope.attribute("gen_ai.usage.complete", false)
                        .attribute("llm.tool_call_count", message.toolCalls == null ? 0 : message.toolCalls.size());
                artifact.success(EvalFiles.JSON.writeValueAsString(message));
                return message;
            }
        }
        private ToolCall call(String name, Map<String, String> args, int index) throws IOException {
            ToolCall call = new ToolCall(); call.id = "script-" + index; call.type = "function";
            call.function = new Function(); call.function.name = name;
            call.function.arguments = EvalFiles.JSON.writeValueAsString(args);
            return call;
        }
    }
}
