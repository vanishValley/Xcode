package com.xu.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmClientStreamAccumulatorTest {

    @Test
    void shouldReconstructFragmentedTextToolCallsAndUsage() {
        List<String> deltas = new ArrayList<>();
        LlmClient.StreamAccumulator accumulator =
                new LlmClient.StreamAccumulator(deltas::add);

        accumulator.accept(chunk(
                "hello ",
                toolDelta(0, "call_1", "write_", "{\"pa"),
                null,
                null));
        LlmClient.Usage usage = new LlmClient.Usage();
        usage.promptTokens = 10;
        usage.completionTokens = 4;
        usage.totalTokens = 14;
        accumulator.accept(chunk(
                "world",
                toolDelta(0, null, "file", "th\":\"README.md\"}"),
                "tool_calls",
                usage));

        LlmClient.Message message = accumulator.toMessage();
        assertEquals("hello world", message.content);
        assertEquals(List.of("hello ", "world"), deltas);
        assertEquals("call_1", message.toolCalls.get(0).id);
        assertEquals("write_file", message.toolCalls.get(0).function.name);
        assertEquals(
                "{\"path\":\"README.md\"}",
                message.toolCalls.get(0).function.arguments);
        assertEquals("tool_calls", message.finishReason);
        assertEquals(14, message.totalTokens);
    }

    private static LlmClient.StreamChunk chunk(
            String content,
            LlmClient.DeltaToolCall tool,
            String finishReason,
            LlmClient.Usage usage) {
        LlmClient.Delta delta = new LlmClient.Delta();
        delta.content = content;
        delta.toolCalls = List.of(tool);
        LlmClient.StreamChoice choice = new LlmClient.StreamChoice();
        choice.delta = delta;
        choice.finishReason = finishReason;
        LlmClient.StreamChunk chunk = new LlmClient.StreamChunk();
        chunk.choices = List.of(choice);
        chunk.usage = usage;
        return chunk;
    }

    private static LlmClient.DeltaToolCall toolDelta(
            int index,
            String id,
            String name,
            String arguments) {
        LlmClient.Function function = new LlmClient.Function();
        function.name = name;
        function.arguments = arguments;
        LlmClient.DeltaToolCall tool = new LlmClient.DeltaToolCall();
        tool.index = index;
        tool.id = id;
        tool.function = function;
        return tool;
    }
}
