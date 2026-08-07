package com.xu.tool.impl;

import com.xu.mcp.LazyMcpClient;
import com.xu.mcp.McpClient;
import com.xu.tool.Tool;

import java.util.Map;

/**
 * 暴露给模型的通用 MCP 启动入口。
 *
 * <p>Chrome 和 DeepWiki 只需使用不同名称与描述创建本类实例；真正的并发
 * 控制和客户端复用由 LazyMcpClient 负责，避免每个服务端重复实现启动工具。</p>
 */
public final class StartMcpTool implements Tool {

    private final String name;
    private final String description;
    private final LazyMcpClient lazyClient;

    public StartMcpTool(
            String name,
            String description,
            LazyMcpClient lazyClient) {
        this.name = name;
        this.description = description;
        this.lazyClient = lazyClient;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        McpClient client = lazyClient.ensureStarted();
        return "MCP 已就绪，动态注册了 "
                + client.registeredToolCount()
                + " 个工具。请在下一轮调用新注册的 MCP 工具。";
    }
}
