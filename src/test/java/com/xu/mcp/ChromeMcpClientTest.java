package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xu.tool.Tool;
import com.xu.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeMcpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldHandshakeDiscoverWhitelistAndForwardToolCalls()
            throws Exception {
        PipedWriter serverOutput = new PipedWriter();
        PipedReader clientOutput = new PipedReader(serverOutput);
        PipedWriter clientInput = new PipedWriter();
        PipedReader serverInput = new PipedReader(clientInput);

        Thread fakeServer = new Thread(
                () -> serveMcp(serverInput, serverOutput),
                "fake-chrome-mcp-server");
        fakeServer.setDaemon(true);
        fakeServer.start();

        ToolRegistry registry = new ToolRegistry();
        try (StdioJsonRpcClient rpc =
                     new StdioJsonRpcClient(clientOutput, clientInput);
             ChromeMcpClient chrome = new ChromeMcpClient(rpc)) {
            assertEquals(1, chrome.initializeAndRegister(registry));

            Tool snapshot =
                    registry.get("mcp__chrome-devtools__take_snapshot");
            assertNotNull(snapshot);
            assertTrue(snapshot.description().startsWith("[Chrome MCP]"));

            // 未进入白名单的性能分析工具不会暴露给 Agent。
            assertNull(registry.get(
                    "mcp__chrome-devtools__performance_start_trace"));

            assertEquals("page snapshot",
                    snapshot.execute(Map.of("verbose", false)));
        }
    }

    private static void serveMcp(PipedReader serverInput,
                                 PipedWriter serverOutput) {
        try {
            BufferedReader input = new BufferedReader(serverInput);
            BufferedWriter output = new BufferedWriter(serverOutput);
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                if (!request.has("id")) {
                    continue; // notifications/initialized 不需要响应。
                }

                ObjectNode response = JSON.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                switch (request.path("method").asText()) {
                    case "initialize" -> response.set(
                            "result", initializeResult());
                    case "tools/list" -> response.set(
                            "result", toolsResult());
                    case "tools/call" -> response.set(
                            "result", callResult(request));
                    default -> throw new IllegalStateException(
                            "unexpected method: "
                                    + request.path("method").asText());
                }

                output.write(JSON.writeValueAsString(response));
                output.newLine();
                output.flush();
            }
        } catch (Exception ignored) {
            // 客户端关闭管道后，假服务端自然结束。
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

    private static void addTool(ArrayNode tools, String name,
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

    private static ObjectNode callResult(JsonNode request) {
        assertEquals("take_snapshot",
                request.path("params").path("name").asText());
        assertTrue(request.path("params").path("arguments")
                .has("verbose"));

        ObjectNode result = JSON.createObjectNode();
        result.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "page snapshot");
        return result;
    }
}
