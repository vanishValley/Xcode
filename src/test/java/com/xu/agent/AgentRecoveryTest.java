package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;
import com.xu.memory.MemoryManager;
import com.xu.observability.Tracing;
import com.xu.tool.Tool;
import com.xu.tool.ToolRegistry;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRecoveryTest {

    @Test
    void preservesCompletedToolEvidenceWhenFollowingModelCallIsCancelled() {
        AtomicInteger sideEffects = new AtomicInteger();
        LlmClient client = new LlmClient("test", "test") {
            private int calls;

            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools)
                    throws InterruptedIOException {
                if (calls++ == 0) {
                    return toolReply("call_write", "mutate");
                }
                throw new InterruptedIOException("cancelled");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("mutate", () -> {
            sideEffects.incrementAndGet();
            return "mutation completed";
        }));
        Agent agent = agent(client, registry);

        Agent.PartialExecutionException failure = assertThrows(
                Agent.PartialExecutionException.class,
                () -> agent.run("change the workspace"));
        assertTrue(failure.cancelled());
        assertTrue(failure.getCause() instanceof InterruptedIOException);

        assertEquals(1, sideEffects.get());
        List<Message> history = agent.getHistory();
        assertEquals(
                List.of("system", "user", "assistant", "tool", "system"),
                history.stream().map(message -> message.role).toList());
        assertEquals("call_write", history.get(3).toolCallId);
        assertEquals("mutation completed", history.get(3).content);
        assertTrue(history.get(4).content.contains("部分外部副作用可能已经发生"));
    }

    @Test
    void fillsUnstartedCallsSoInterruptedToolBatchRemainsProtocolValid() {
        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> tools) {
                Message reply = toolReply("call_first", "interrupting");
                reply.toolCalls = List.of(
                        reply.toolCalls.get(0),
                        toolCall("call_second", "never_started"));
                return reply;
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("interrupting", () -> {
            Thread.currentThread().interrupt();
            return "side effect may have completed";
        }));
        registry.register(tool("never_started", () -> "unexpected"));
        Agent agent = agent(client, registry);

        try {
            Agent.PartialExecutionException failure = assertThrows(
                    Agent.PartialExecutionException.class,
                    () -> agent.run("run both tools"));
            assertTrue(failure.cancelled());

            List<Message> history = agent.getHistory();
            List<Message> results = history.stream()
                    .filter(message -> "tool".equals(message.role))
                    .toList();
            assertEquals(2, results.size());
            assertEquals("call_first", results.get(0).toolCallId);
            assertEquals("call_second", results.get(1).toolCallId);
            assertTrue(results.get(1).content.contains("执行状态未知"));
        } finally {
            Thread.interrupted();
        }
    }

    private static Agent agent(LlmClient client, ToolRegistry registry) {
        return new Agent(
                client,
                registry,
                new MemoryManager(),
                null,
                "worker",
                Tracing.noop(),
                UiEventSink.noop(),
                new CancellationToken());
    }

    private static Message toolReply(String id, String name) {
        Message reply = new Message("assistant", null);
        reply.toolCalls = List.of(toolCall(id, name));
        return reply;
    }

    private static LlmClient.ToolCall toolCall(String id, String name) {
        LlmClient.ToolCall call = new LlmClient.ToolCall();
        call.id = id;
        call.function = new LlmClient.Function();
        call.function.name = name;
        call.function.arguments = "{}";
        return call;
    }

    private static Tool tool(String name, ThrowingAction action) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments)
                    throws Exception {
                return action.run();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingAction {
        String run() throws Exception;
    }
}
