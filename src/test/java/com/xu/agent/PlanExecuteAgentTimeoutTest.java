package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;
import com.xu.plan.PlanStore;
import com.xu.observability.Tracing;
import com.xu.plan.Task;
import com.xu.plan.ExecutionPlan;
import com.xu.tool.Tool;
import com.xu.tool.ToolRegistry;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTimeoutTest {

    @TempDir
    Path tempDir;

    @Test
    void timeoutAfterMutationStopsWithoutReplanOrReplay() throws Exception {
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();

        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools)
                    throws InterruptedIOException {
                String prompt = messages.get(messages.size() - 1).content;
                if (prompt != null
                        && prompt.startsWith("请为以下任务生成执行计划")) {
                    return message("""
                            [
                              {"id":"task_0","description":"执行一次变更","dependencies":[]},
                              {"id":"task_1","description":"依赖前一步","dependencies":["task_0"]}
                            ]
                            """);
                }
                if (prompt != null && prompt.contains("重新规划后续步骤")) {
                    replanCalls.incrementAndGet();
                    return message("[]");
                }
                if (Thread.currentThread().getName()
                        .startsWith("plan-worker")) {
                    if (workerCalls.getAndIncrement() == 0) {
                        Message reply = new Message("assistant", null);
                        reply.toolCalls = List.of(
                                toolCall("call_mutate", "mutate"));
                        return reply;
                    }
                    try {
                        Thread.sleep(10_000L);
                    } catch (InterruptedException interrupted) {
                        throw new InterruptedIOException(
                                "worker request cancelled");
                    }
                }
                throw new AssertionError("unexpected LLM request");
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "mutate";
            }

            @Override
            public String description() {
                return "test mutation";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                sideEffects.incrementAndGet();
                return "changed";
            }
        });

        PlanStore store = new PlanStore(tempDir);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                store,
                null,
                null,
                null,
                Tracing.noop(),
                UiEventSink.noop(),
                new CancellationToken(),
                150L);

        String report = agent.execute("perform one mutation safely");

        assertEquals(1, sideEffects.get());
        assertEquals(0, replanCalls.get());
        assertTrue(report.contains("外部副作用状态未知"));
        PlanStore.Checkpoint checkpoint = store.load();
        assertNotNull(checkpoint);
        assertTrue(store.hasInterruptedTasks(checkpoint));
        assertEquals(
                Task.Status.PENDING,
                checkpoint.plan().getAllTasks().get(1).getStatus());
    }

    @Test
    void modelFailureAfterMutationIsUncertainAndNeverReplanned()
            throws Exception {
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();

        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools) throws IOException {
                String prompt = messages.get(messages.size() - 1).content;
                if (prompt != null
                        && prompt.startsWith("请为以下任务生成执行计划")) {
                    return message("""
                            [
                              {"id":"task_0","description":"执行一次变更","dependencies":[]},
                              {"id":"task_1","description":"依赖前一步","dependencies":["task_0"]}
                            ]
                            """);
                }
                if (prompt != null && prompt.contains("重新规划后续步骤")) {
                    replanCalls.incrementAndGet();
                    return message("[]");
                }
                if (Thread.currentThread().getName()
                        .startsWith("plan-worker")) {
                    if (workerCalls.getAndIncrement() == 0) {
                        Message reply = new Message("assistant", null);
                        reply.toolCalls = List.of(
                                toolCall("call_mutate", "mutate"));
                        return reply;
                    }
                    throw new IOException("network lost after mutation");
                }
                throw new AssertionError("unexpected LLM request");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(mutationTool(sideEffects));
        PlanStore store = new PlanStore(
                tempDir.resolve("model-failure"));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                store,
                null,
                null,
                null,
                Tracing.noop(),
                UiEventSink.noop(),
                new CancellationToken(),
                5_000L);

        String report = agent.execute("perform one mutation safely");

        assertEquals(1, sideEffects.get());
        assertEquals(0, replanCalls.get());
        assertTrue(report.contains("外部副作用状态未知"));
        PlanStore.Checkpoint checkpoint = store.load();
        assertNotNull(checkpoint);
        assertTrue(store.hasInterruptedTasks(checkpoint));
    }

    @Test
    void refusesToStartWorkersWhenStartedCheckpointIsNotDurable()
            throws Exception {
        AtomicInteger workerCalls = new AtomicInteger();
        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools) {
                String prompt = messages.get(messages.size() - 1).content;
                if (prompt != null
                        && prompt.startsWith("请为以下任务生成执行计划")) {
                    return message("""
                            [
                              {"id":"task_0","description":"执行变更","dependencies":[]}
                            ]
                            """);
                }
                workerCalls.incrementAndGet();
                throw new AssertionError(
                        "worker must not start without a durable checkpoint");
            }
        };
        Path notDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(notDirectory, "blocking file");
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                new ToolRegistry(),
                new PlanStore(notDirectory),
                null,
                null,
                null,
                Tracing.noop(),
                UiEventSink.noop(),
                new CancellationToken(),
                5_000L);

        IOException failure = assertThrows(
                IOException.class,
                () -> agent.execute("perform a guarded mutation"));

        assertTrue(failure.getMessage().contains("未启动任何 Worker"));
        assertEquals(0, workerCalls.get());
    }

    @Test
    void reviewerRejectionAfterMutationDoesNotRunFreshWorker()
            throws Exception {
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools) {
                String system = messages.get(0).content;
                if (system.contains("任务规划专家")) {
                    return message("""
                            [
                              {"id":"task_0","description":"写入产物","dependencies":[]}
                            ]
                            """);
                }
                if (system.contains("代码审查专家")) {
                    reviewerCalls.incrementAndGet();
                    return message("""
                            {"approved":false,"issues":["需要修正"],"suggestions":[]}
                            """);
                }
                boolean hasToolResult = messages.stream()
                        .anyMatch(item -> "tool".equals(item.role));
                if (!hasToolResult) {
                    Message reply = new Message("assistant", null);
                    reply.toolCalls = List.of(
                            toolCall("call_write", "write_file"));
                    return reply;
                }
                return message("worker reports completion");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "write_file";
            }

            @Override
            public String description() {
                return "test mutation";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                sideEffects.incrementAndGet();
                return "changed";
            }
        });
        PlanStore store = new PlanStore(
                tempDir.resolve("review-rejection"));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                store,
                null,
                null,
                null,
                Tracing.noop(),
                UiEventSink.noop(),
                new CancellationToken(),
                5_000L);

        String report = agent.execute("write once");

        assertEquals(1, sideEffects.get());
        assertEquals(1, reviewerCalls.get());
        assertTrue(report.contains("外部副作用状态未知"));
        PlanStore.Checkpoint checkpoint = store.load();
        assertNotNull(checkpoint);
        assertTrue(store.hasInterruptedTasks(checkpoint));
    }

    @Test
    void directResumeCannotBypassInterruptedCheckpointGuard()
            throws Exception {
        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools) {
                llmCalls.incrementAndGet();
                throw new AssertionError(
                        "interrupted checkpoint must not reach LLM");
            }
        };
        ExecutionPlan plan = new ExecutionPlan();
        Task interrupted = new Task(
                "task_0", "possibly changed state", List.of());
        interrupted.setStatus(Task.Status.FAILED);
        interrupted.setResult(
                PlanStore.INTERRUPTED_RESULT_PREFIX
                        + "unknown external state");
        Task dependent = new Task(
                "task_1", "must not run", List.of("task_0"));
        plan.addTask(interrupted);
        plan.addTask(dependent);
        PlanStore.Checkpoint checkpoint =
                new PlanStore.Checkpoint("x", 0, plan);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                new ToolRegistry());

        String report = agent.resume(checkpoint);

        assertEquals(0, llmCalls.get());
        assertTrue(report.contains("外部副作用状态未知"));
        assertEquals(Task.Status.PENDING, dependent.getStatus());
    }

    private static Message message(String content) {
        return new Message("assistant", content);
    }

    private static LlmClient.ToolCall toolCall(String id, String name) {
        LlmClient.ToolCall call = new LlmClient.ToolCall();
        call.id = id;
        call.function = new LlmClient.Function();
        call.function.name = name;
        call.function.arguments = "{}";
        return call;
    }

    private static Tool mutationTool(AtomicInteger sideEffects) {
        return new Tool() {
            @Override
            public String name() {
                return "mutate";
            }

            @Override
            public String description() {
                return "test mutation";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                sideEffects.incrementAndGet();
                return "changed";
            }
        };
    }
}
