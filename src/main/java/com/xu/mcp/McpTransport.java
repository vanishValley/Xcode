package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;

/**
 * MCP 消息传输边界。
 *
 * <p>上层 {@link McpClient} 只处理 initialize、tools/list、tools/call 等
 * MCP 语义；本接口负责 JSON-RPC 消息通过 stdio 或 Streamable HTTP 到达服务端。
 * 因此增加新的传输方式时，不需要复制 MCP 生命周期和工具适配代码。</p>
 */
public interface McpTransport extends AutoCloseable {

    /** 发送有请求 ID 的 JSON-RPC 请求，并返回 result 字段。 */
    JsonNode request(String method, JsonNode params, Duration timeout)
            throws IOException;

    /** 发送没有请求 ID 的 JSON-RPC 通知。 */
    void notification(String method, JsonNode params) throws IOException;

    /**
     * 告知传输层 MCP 握手最终协商出的协议版本。
     * stdio 不需要额外处理；HTTP 会把它放入后续请求头。
     */
    default void setProtocolVersion(String protocolVersion) {
    }

    @Override
    void close();
}
