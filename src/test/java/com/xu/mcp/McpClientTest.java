package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xu.observability.Tracing;
import com.xu.tool.Tool;
import com.xu.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldHandshakeDiscoverWhitelistAndForwardToolCalls()
            throws Exception {
        ToolRegistry registry = new ToolRegistry();
        FakeTransport transport = new FakeTransport();

        try (McpClient client = McpClient.connect(
                "chrome-devtools",
                "Chrome DevTools",
                transport,
                Set.of("take_snapshot"),
                true,
                registry,
                Tracing.noop())) {
            assertEquals(1, client.registeredToolCount());
            assertTrue(transport.initializedNotification);
            assertEquals("2025-11-25", transport.protocolVersion);

            Tool snapshot = registry.get(
                    "mcp__chrome-devtools__take_snapshot");
            assertNotNull(snapshot);
            assertTrue(snapshot.description()
                    .startsWith("[Chrome DevTools MCP]"));

            // 不在白名单中的性能工具不会暴露给 Agent。
            assertNull(registry.get(
                    "mcp__chrome-devtools__performance_start_trace"));
            assertEquals("page snapshot",
                    snapshot.execute(Map.of("verbose", false)));
        }
        assertTrue(transport.closed);
    }

    private static final class FakeTransport implements McpTransport {
        private String protocolVersion;
        private boolean initializedNotification;
        private boolean closed;

        @Override
        public JsonNode request(
                String method,
                JsonNode params,
                Duration timeout) throws IOException {
            return switch (method) {
                case "initialize" -> initializeResult();
                case "tools/list" -> toolsResult();
                case "tools/call" -> callResult(params);
                default -> throw new IOException(
                        "unexpected method: " + method);
            };
        }

        @Override
        public void notification(String method, JsonNode params) {
            initializedNotification =
                    "notifications/initialized".equals(method);
        }

        @Override
        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", "2025-11-25");
        result.set("capabilities", JSON.createObjectNode());
        return result;
    }

    private static ObjectNode toolsResult() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        addTool(tools, "take_snapshot", "读取页面结构");
        addTool(tools, "performance_start_trace", "性能分析");
        return result;
    }

    private static void addTool(
            ArrayNode tools,
            String name,
            String description) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);
        tool.putObject("inputSchema")
                .put("type", "object")
                .putObject("properties")
                .putObject("verbose")
                .put("type", "boolean");
    }

    private static ObjectNode callResult(JsonNode params) {
        assertEquals("take_snapshot", params.path("name").asText());
        assertTrue(params.path("arguments").has("verbose"));

        ObjectNode result = JSON.createObjectNode();
        result.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "page snapshot");
        return result;
    }
}
