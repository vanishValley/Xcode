package com.xu.observability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 同一份 Span 数据的任务级汇总；实时预算和离线报告使用相同口径。 */
public record ExecutionSnapshot(long inputTokens, long outputTokens, boolean usageComplete,
                                int llmCalls, int requestedTools, int executedTools, boolean faultTriggered) {
    public static ExecutionSnapshot recover(Path events) throws IOException {
        Counter counter = new Counter();
        if (Files.exists(events)) {
            ObjectMapper mapper = new ObjectMapper();
            for (String line : Files.readAllLines(events)) {
                try {
                    Map<String,Object> event = mapper.readValue(line, new TypeReference<>() {});
                    @SuppressWarnings("unchecked")
                    Map<String,Object> attributes = (Map<String,Object>) event.getOrDefault("attributes", Map.of());
                    counter.accept((String) event.get("type"), (String) event.get("name"), attributes);
                } catch (IOException ignored) {
                    // 进程被杀时最后一条 JSON 可能不完整；前面的已知用量仍可恢复。
                    counter.complete = false;
                }
            }
        }
        return counter.snapshot();
    }

    static final class Counter {
        private long input, output;
        private int calls, completedCalls, requested, executed;
        private boolean complete = true, fault;
        synchronized void accept(String type, String name, Map<String,Object> attributes) {
            if ("fault".equals(type)) fault = true;
            if ("span.start".equals(type)) {
                if ("llm.chat".equals(name)) calls++;
                if ("tool.execute".equals(name)) executed++;
            }
            if ("span.end".equals(type) && "llm.chat".equals(name)) {
                completedCalls++;
                input += number(attributes, "gen_ai.usage.input_tokens");
                output += number(attributes, "gen_ai.usage.output_tokens");
                requested += (int) number(attributes, "llm.tool_call_count");
                complete &= Boolean.TRUE.equals(attributes.get("gen_ai.usage.complete"));
            }
        }
        synchronized ExecutionSnapshot snapshot() {
            return new ExecutionSnapshot(input, output, complete && calls == completedCalls,
                    calls, requested, executed, fault);
        }
        private static long number(Map<String,Object> values, String key) {
            return values.get(key) instanceof Number n ? Math.max(0,n.longValue()) : 0;
        }
    }
}
