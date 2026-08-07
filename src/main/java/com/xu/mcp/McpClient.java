package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.tool.Tool;
import com.xu.tool.ToolRegistry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 与传输方式无关的 MCP 客户端。
 *
 * <p>本类统一实现协议握手、工具发现、白名单过滤、动态 Tool 适配和
 * tools/call。Chrome 与 DeepWiki 的差异只体现在 McpTransport 和配置上，
 * 不再为每个 MCP Server 复制一套客户端代码。</p>
 */
public final class McpClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUESTED_PROTOCOL = "2025-11-25";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private final String serverName;
    private final String displayName;
    private final McpTransport transport;
    private final Set<String> allowedTools;
    private final boolean serializeToolCalls;
    private final Tracing tracing;
    private final Object toolCallLock = new Object();

    private int registeredToolCount;

    private McpClient(
            String serverName,
            String displayName,
            McpTransport transport,
            Set<String> allowedTools,
            boolean serializeToolCalls,
            Tracing tracing) {
        if (serverName == null
                || !serverName.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException(
                    "MCP serverName 只能包含小写字母、数字和连字符");
        }
        this.serverName = serverName;
        this.displayName = displayName == null || displayName.isBlank()
                ? serverName : displayName.strip();
        this.transport = transport;
        this.allowedTools = Set.copyOf(allowedTools);
        this.serializeToolCalls = serializeToolCalls;
        this.tracing = tracing == null ? Tracing.noop() : tracing;
    }

    /**
     * 完成 MCP 握手和工具注册后再返回客户端。
     * 任何阶段失败都会关闭 Transport，调用方不会拿到半初始化实例。
     */
    public static McpClient connect(
            String serverName,
            String displayName,
            McpTransport transport,
            Set<String> allowedTools,
            boolean serializeToolCalls,
            ToolRegistry registry,
            Tracing tracing) throws IOException {
        McpClient client = new McpClient(
                serverName,
                displayName,
                transport,
                allowedTools,
                serializeToolCalls,
                tracing);
        try {
            client.initialize();
            client.discoverAndRegister(registry);
            return client;
        } catch (Exception error) {
            client.close();
            if (error instanceof IOException io) throw io;
            throw new IOException(displayName + " MCP 初始化失败", error);
        }
    }

    public int registeredToolCount() {
        return registeredToolCount;
    }

    private void initialize() throws IOException {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", REQUESTED_PROTOCOL);
        params.set("capabilities", JSON.createObjectNode());
        params.putObject("clientInfo")
                .put("name", "xcode")
                .put("version", "1.0");

        JsonNode result = transport.request(
                "initialize", params, Duration.ofSeconds(60));
        String protocol = result.path("protocolVersion").asText();
        if (!SUPPORTED_PROTOCOLS.contains(protocol)) {
            throw new IOException("不支持的 MCP 协议版本：" + protocol);
        }

        // HTTP 传输从这一刻起需要在 Header 中携带协商后的版本。
        transport.setProtocolVersion(protocol);
        transport.notification(
                "notifications/initialized", JSON.createObjectNode());
    }

    private void discoverAndRegister(ToolRegistry registry)
            throws IOException {
        List<Tool> discovered = new ArrayList<>();
        for (ToolSpec spec : listTools()) {
            if (allowedTools.contains(spec.name())) {
                discovered.add(new McpToolAdapter(spec));
            }
        }

        // 所有分页和对象构造都在锁外完成，最后才原子发布完整工具集合。
        registry.registerAll(discovered);
        registeredToolCount = discovered.size();
    }

    private List<ToolSpec> listTools() throws IOException {
        List<ToolSpec> tools = new ArrayList<>();
        String cursor = "";

        for (int page = 0; page < 100; page++) {
            ObjectNode params = JSON.createObjectNode();
            if (!cursor.isBlank()) params.put("cursor", cursor);

            JsonNode result = transport.request(
                    "tools/list", params, Duration.ofSeconds(30));
            for (JsonNode tool : result.path("tools")) {
                String name = tool.path("name").asText();
                if (!name.isBlank()) {
                    tools.add(new ToolSpec(
                            name,
                            tool.path("description").asText(),
                            schema(tool.path("inputSchema"))));
                }
            }

            cursor = result.path("nextCursor").asText();
            if (cursor.isBlank()) return tools;
        }
        throw new IOException("MCP tools/list 分页超过上限");
    }

    private String callTool(
            String toolName,
            Map<String, Object> arguments) throws IOException {
        if (!serializeToolCalls) {
            return doCallTool(toolName, arguments);
        }
        // Chrome 页面状态是共享资源，需要串行化；无状态远程 MCP 可以并行。
        synchronized (toolCallLock) {
            return doCallTool(toolName, arguments);
        }
    }

    private String doCallTool(
            String toolName,
            Map<String, Object> arguments) throws IOException {
        try (TraceScope scope = tracing.startClient("mcp.call")
                .attribute("mcp.server", serverName)
                .attribute("mcp.method", "tools/call")
                .attribute("mcp.tool", toolName)
                .attribute("mcp.timeout_ms", 60_000L)) {
            try {
                ObjectNode params = JSON.createObjectNode();
                params.put("name", toolName);
                params.set("arguments", JSON.valueToTree(arguments));

                JsonNode result = transport.request(
                        "tools/call", params, Duration.ofSeconds(60));
                String response = toolResult(result, scope);
                scope.attribute("mcp.result_chars", response.length());
                return response;
            } catch (IOException error) {
                scope.fail(error);
                throw error;
            }
        }
    }

    private static String toolResult(JsonNode result, TraceScope scope)
            throws IOException {
        StringBuilder text = new StringBuilder();
        if (result.path("isError").asBoolean()) {
            scope.error("MCP_TOOL_ERROR",
                    "MCP server returned isError=true");
            text.append("MCP_TOOL_ERROR\n");
        }

        for (JsonNode item : result.path("content")) {
            switch (item.path("type").asText()) {
                case "text" -> text.append(item.path("text").asText());
                case "image" -> text.append(
                        "\n[图片结果未传入文本上下文]\n");
                case "resource", "resource_link" -> text.append(
                        "\n[资源结果未传入文本上下文]\n");
                default -> {
                    // 未识别类型不应破坏其他可用文本结果。
                }
            }
        }

        if (text.isEmpty() && result.has("structuredContent")) {
            return JSON.writeValueAsString(result.get("structuredContent"));
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return JSON.convertValue(schema, LinkedHashMap.class);
    }

    @Override
    public void close() {
        transport.close();
    }

    /** 将 MCP tools/list 返回的动态描述适配成项目统一的 Tool 接口。 */
    private final class McpToolAdapter implements Tool {
        private final ToolSpec spec;

        private McpToolAdapter(ToolSpec spec) {
            this.spec = spec;
        }

        @Override
        public String name() {
            return "mcp__" + serverName + "__" + spec.name();
        }

        @Override
        public String description() {
            return "[" + displayName + " MCP] " + spec.description();
        }

        @Override
        public Map<String, Object> inputSchema() {
            return spec.inputSchema();
        }

        @Override
        public String execute(Map<String, Object> arguments)
                throws Exception {
            return callTool(spec.name(), arguments);
        }
    }

    private record ToolSpec(
            String name,
            String description,
            Map<String, Object> inputSchema) {
    }
}
