package com.xu.tool;

import com.xu.llm.LlmClient;
import com.xu.observability.Tracing;
import com.xu.ui.QueueUiEventSink;
import com.xu.ui.UiEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ToolExecutorUiEventTest {

    @Test
    void shouldEmitExactlyOneSafeStartAndCompletion() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public String description() {
                return "demo";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "raw-result-never-print-me";
            }
        });
        QueueUiEventSink events = new QueueUiEventSink();
        ToolExecutor executor = new ToolExecutor(
                registry, Tracing.noop(), events, "task_1");
        LlmClient.ToolCall call = new LlmClient.ToolCall();
        call.id = "call_1";
        call.function = new LlmClient.Function();
        call.function.name = "demo";
        call.function.arguments =
                "{\"api_key\":\"never-print-me\",\"path\":\"README.md\"}";

        executor.execute(call);

        UiEvent.ToolStarted started = assertInstanceOf(
                UiEvent.ToolStarted.class, events.poll());
        UiEvent.ToolCompleted completed = assertInstanceOf(
                UiEvent.ToolCompleted.class, events.poll());
        assertEquals("call_1", started.callId());
        assertEquals("call_1", completed.callId());
        assertFalse(started.toString().contains("never-print-me"));
        assertFalse(completed.toString().contains("never-print-me"));
        assertEquals("", completed.result().content());
        assertEquals(null, events.poll());
    }
}
