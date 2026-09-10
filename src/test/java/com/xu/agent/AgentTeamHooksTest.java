package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.memory.MemoryManager;
import com.xu.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTeamHooksTest {
    @Test void pauseCompletesToolBatchWithoutExecutingRemainingCallsAndCanContinue() throws Exception {
        AtomicBoolean pause = new AtomicBoolean();
        AtomicInteger effects = new AtomicInteger(), requests = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("ask", () -> pause.set(true)));
        tools.register(tool("effect", effects::incrementAndGet));
        LlmClient model = new LlmClient("", "fake") {
            @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> schema) {
                if (requests.incrementAndGet() == 1) {
                    Message reply = new Message("assistant", null);
                    reply.toolCalls = List.of(call("a", "ask"), call("b", "effect")); return reply;
                }
                return new Message("assistant", "继续完成");
            }
        };
        Agent agent = new Agent(model, tools, new MemoryManager(), "worker");
        AgentRunHooks hooks = new AgentRunHooks() { @Override public boolean pauseRequested() { return pause.get(); } };
        assertEquals("NEEDS_INPUT", agent.runCoordinated("调查", hooks).outcome());
        assertEquals(0, effects.get()); assertEquals(1, requests.get());
        List<LlmClient.Message> results = agent.getHistory().stream().filter(m -> "tool".equals(m.role)).toList();
        assertEquals(2, results.size()); assertEquals("b", results.get(1).toolCallId);
        assertTrue(results.get(1).content.contains("未执行"));
        pause.set(false);
        assertEquals("SUCCESS", agent.runCoordinated("主 Agent 回答", hooks).outcome());
    }

    @Test void finishGateRejectsPrematureAnswerAndInjectsReason() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        LlmClient model = new LlmClient("", "fake") {
            @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> schema) {
                int n = requests.incrementAndGet();
                if (n == 2) assertTrue(messages.stream().anyMatch(m -> m.content != null && m.content.contains("仍在执行")));
                return new Message("assistant", n == 1 ? "过早完成" : "验证后完成");
            }
        };
        Agent agent = new Agent(model, new ToolRegistry(), new MemoryManager(), "lead");
        Agent.RunResult result = agent.runCoordinated("task", new AgentRunHooks() {
            @Override public String finishBlocker() { return requests.get() == 1 ? "成员仍在执行" : ""; }
        });
        assertEquals("验证后完成", result.content()); assertEquals(2, result.llmCalls());
    }

    private static LlmClient.ToolCall call(String id, String name) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(); call.id = id;
        call.function = new LlmClient.Function(); call.function.name = name; call.function.arguments = "{}"; return call;
    }
    private static Tool tool(String name, Runnable action) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String execute(Map<String, Object> args) { action.run(); return "ok"; }
        };
    }
}
