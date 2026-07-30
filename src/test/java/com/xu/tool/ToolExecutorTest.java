package com.xu.tool;

import com.xu.llm.LlmClient;
import com.xu.observability.Tracing;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    @Test
    void shouldExecuteRegisteredTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("echo", args -> "value=" + args.get("value")));

        ToolExecutionResult result =
                new ToolExecutor(registry, Tracing.noop())
                        .execute(call("echo", "{\"value\":\"ok\"}"));

        assertTrue(result.success());
        assertEquals("value=ok", result.content());
    }

    @Test
    void shouldConvertToolExceptionToRecoverableResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("broken", args -> {
            throw new IllegalStateException("boom");
        }));

        ToolExecutionResult result =
                new ToolExecutor(registry, Tracing.noop())
                        .execute(call("broken", "{}"));

        assertFalse(result.success());
        assertEquals("IllegalStateException", result.errorType());
        assertTrue(result.content().contains("boom"));
    }

    @Test
    void shouldReportUnknownToolWithoutThrowing() {
        ToolExecutionResult result =
                new ToolExecutor(new ToolRegistry(), Tracing.noop())
                        .execute(call("missing", "{}"));

        assertFalse(result.success());
        assertEquals("TOOL_NOT_FOUND", result.errorType());
    }

    @Test
    void shouldPreserveStructuredCommandFailure() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "command";
            }

            @Override
            public String description() {
                return "test command";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "退出码: 7";
            }

            @Override
            public ToolExecutionResult executeObserved(
                    Map<String, Object> arguments) {
                return ToolExecutionResult.command(
                        "退出码: 7", 7, false);
            }
        });

        ToolExecutionResult result =
                new ToolExecutor(registry, Tracing.noop())
                        .execute(call("command", "{}"));

        assertFalse(result.success());
        assertEquals("COMMAND_EXIT_NON_ZERO", result.errorType());
        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
    }

    private static LlmClient.ToolCall call(
            String name, String arguments) {
        LlmClient.ToolCall call = new LlmClient.ToolCall();
        call.id = "call_1";
        call.function = new LlmClient.Function();
        call.function.name = name;
        call.function.arguments = arguments;
        return call;
    }

    private static Tool tool(String name, ToolBody body) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments)
                    throws Exception {
                return body.execute(arguments);
            }
        };
    }

    @FunctionalInterface
    private interface ToolBody {
        String execute(Map<String, Object> arguments) throws Exception;
    }
}
