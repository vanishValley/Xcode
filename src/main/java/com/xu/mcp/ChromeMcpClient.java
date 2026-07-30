package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.tool.Tool;
import com.xu.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chrome DevTools MCP 适配器。
 * 负责 MCP 握手、工具发现、白名单注册和 tools/call。
 */
public final class ChromeMcpClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    // Chrome 服务端还有性能、追踪等工具；联网场景只向 Agent 暴露必要子集。
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "navigate_page", "take_snapshot", "wait_for",
            "list_pages", "new_page", "select_page",
            "click", "fill", "fill_form", "take_screenshot");

    private final StdioJsonRpcClient rpc;
    private final Tracing tracing;
    private int registeredToolCount;

    ChromeMcpClient(StdioJsonRpcClient rpc) {
        this(rpc, Tracing.noop());
    }

    ChromeMcpClient(StdioJsonRpcClient rpc, Tracing tracing) {
        this.rpc = rpc;
        this.tracing = tracing;
    }

    public static ChromeMcpClient start(ToolRegistry registry,
                                        Map<String, String> fileEnv,
                                        Path workingDirectory)
            throws IOException {
        return start(registry, fileEnv, workingDirectory, Tracing.noop());
    }

    public static ChromeMcpClient start(ToolRegistry registry,
                                        Map<String, String> fileEnv,
                                        Path workingDirectory,
                                        Tracing tracing)
            throws IOException {
        StdioJsonRpcClient rpc = new StdioJsonRpcClient(
                command(fileEnv),
                childEnvironment(),
                workingDirectory);
        ChromeMcpClient client = new ChromeMcpClient(rpc, tracing);

        try {
            client.initializeAndRegister(registry);
            return client;
        } catch (Exception e) {
            client.close();
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException("Chrome MCP 启动失败", e);
        }
    }

    int initializeAndRegister(ToolRegistry registry) throws IOException {
        initialize();
        registerTools(registry);
        return registeredToolCount;
    }

    public int registeredToolCount() {
        return registeredToolCount;
    }

    private void initialize() throws IOException {
        // MCP 生命周期：initialize 请求成功后，再发送 initialized 通知。
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", "2025-11-25");
        params.set("capabilities", JSON.createObjectNode());
        params.putObject("clientInfo")
                .put("name", "xcode")
                .put("version", "1.0");

        JsonNode result = rpc.request(
                "initialize", params, Duration.ofSeconds(60));
        String protocol = result.path("protocolVersion").asText();
        if (!SUPPORTED_PROTOCOLS.contains(protocol)) {
            throw new IOException("不支持的 MCP 协议版本：" + protocol);
        }

        rpc.notification(
                "notifications/initialized", JSON.createObjectNode());
    }

    private void registerTools(ToolRegistry registry) throws IOException {
        for (ToolSpec spec : listTools()) {
            if (!ALLOWED_TOOLS.contains(spec.name())) continue;

            // 加命名空间，防止多个 MCP 服务端出现同名工具。
            String registeredName =
                    "mcp__chrome-devtools__" + spec.name();
            registry.register(new Tool() {
                @Override
                public String name() {
                    return registeredName;
                }

                @Override
                public String description() {
                    return "[Chrome MCP] " + spec.description();
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
            });
            registeredToolCount++;
        }
    }

    private List<ToolSpec> listTools() throws IOException {
        List<ToolSpec> tools = new ArrayList<>();
        String cursor = "";

        // MCP 的 tools/list 支持游标分页，不能假设所有工具都在第一页。
        for (int page = 0; page < 100; page++) {
            ObjectNode params = JSON.createObjectNode();
            if (!cursor.isBlank()) params.put("cursor", cursor);

            JsonNode result = rpc.request(
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

    private synchronized String callTool(
            String toolName, Map<String, Object> arguments)
            throws IOException {
        // MCP 是进程外调用，因此使用 CLIENT Span，与本地 tool.execute 区分。
        // 典型父子关系为 tool.execute -> mcp.call。
        try (TraceScope scope = tracing.startClient("mcp.call")
                .attribute("mcp.server", "chrome-devtools")
                .attribute("mcp.method", "tools/call")
                .attribute("mcp.tool", toolName)
                .attribute("mcp.timeout_ms", 60_000L)) {
            try {
                // Tool.execute 的 Map 参数在这里转换成 MCP tools/call。
                ObjectNode params = JSON.createObjectNode();
                params.put("name", toolName);
                params.set("arguments", JSON.valueToTree(arguments));

                JsonNode result = rpc.request(
                        "tools/call", params, Duration.ofSeconds(60));
                StringBuilder text = new StringBuilder();

                if (result.path("isError").asBoolean()) {
                    scope.error("MCP_TOOL_ERROR",
                            "MCP server returned isError=true");
                    text.append("MCP_TOOL_ERROR\n");
                }
                for (JsonNode item : result.path("content")) {
                    String type = item.path("type").asText();
                    if ("text".equals(type)) {
                        text.append(item.path("text").asText());
                    } else if ("image".equals(type)) {
                        text.append("\n[图片结果未传入文本上下文]\n");
                    }
                }

                String response = text.length() == 0
                        && result.has("structuredContent")
                        ? JSON.writeValueAsString(
                                result.get("structuredContent"))
                        : text.toString();
                scope.attribute("mcp.result_chars", response.length());
                return response;
            } catch (IOException error) {
                scope.fail(error);
                throw error;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return JSON.convertValue(schema, LinkedHashMap.class);
    }

    private static List<String> command(Map<String, String> fileEnv) {
        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase().contains("win");

        if (windows) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add("npx");
        command.add("-y");
        command.add(value(fileEnv, "CHROME_MCP_PACKAGE",
                "chrome-devtools-mcp@1.6.0"));
        command.add("--isolated=true");
        if (flag(fileEnv, "CHROME_MCP_HEADLESS", true)) {
            command.add("--headless=true");
        }
        command.add("--no-usage-statistics");
        return command;
    }

    private static Map<String, String> childEnvironment() {
        return Map.of(
                "CHROME_DEVTOOLS_MCP_NO_UPDATE_CHECKS", "1",
                "CHROME_DEVTOOLS_MCP_NO_USAGE_STATISTICS", "1",
                "npm_config_cache", Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "xcode-npm-cache").toString());
    }

    static boolean flag(Map<String, String> fileEnv,
                        String key, boolean defaultValue) {
        return switch (value(fileEnv, key,
                Boolean.toString(defaultValue)).toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    static String value(Map<String, String> fileEnv,
                        String key, String defaultValue) {
        String systemValue = System.getenv(key);
        if (systemValue != null) return systemValue.strip();
        String fileValue = fileEnv == null ? null : fileEnv.get(key);
        return fileValue == null ? defaultValue : fileValue.strip();
    }

    @Override
    public void close() {
        rpc.close();
    }

    private record ToolSpec(
            String name,
            String description,
            Map<String, Object> inputSchema) {}
}
