package com.xu.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xu.http.OkHttpCallExecutor;
import com.xu.mcp.McpTransport;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Streamable HTTP 传输实现。
 *
 * <p>每条 JSON-RPC 消息使用一次 HTTP POST。服务端既可以返回普通 JSON，
 * 也可以返回 SSE 流；初始化响应还可能通过 {@code Mcp-Session-Id} 建立有状态
 * 会话。本类封装这些传输差异，上层 McpClient 无需感知 HTTP Header 和 SSE。</p>
 */
public final class StreamableHttpMcpTransport implements McpTransport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final String ACCEPT =
            "application/json, text/event-stream";

    private final String endpoint;
    private final Map<String, String> headers;
    private final OkHttpClient httpClient;
    private final boolean ownsHttpClient;
    private final AtomicLong requestIds = new AtomicLong(1);

    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile boolean closed;

    public StreamableHttpMcpTransport(
            String endpoint,
            Map<String, String> headers) {
        this(
                endpoint,
                headers,
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(70))
                        .callTimeout(Duration.ofSeconds(70))
                        .build(),
                true);
    }

    /** 测试入口：允许注入指向本地假服务端的 OkHttpClient。 */
    StreamableHttpMcpTransport(
            String endpoint,
            Map<String, String> headers,
            OkHttpClient httpClient) {
        this(endpoint, headers, httpClient, false);
    }

    private StreamableHttpMcpTransport(
            String endpoint,
            Map<String, String> headers,
            OkHttpClient httpClient,
            boolean ownsHttpClient) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MCP HTTP endpoint 不能为空");
        }
        this.endpoint = endpoint.strip();
        this.headers = headers == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
    }

    @Override
    public JsonNode request(String method, JsonNode params, Duration timeout)
            throws IOException {
        ensureOpen();
        long id = requestIds.getAndIncrement();
        ObjectNode message = message(method, params);
        message.put("id", id);
        return post(message, method, id, timeout, true);
    }

    @Override
    public void notification(String method, JsonNode params)
            throws IOException {
        ensureOpen();
        post(message(method, params), method, -1,
                Duration.ofSeconds(30), false);
    }

    @Override
    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    private JsonNode post(
            ObjectNode message,
            String method,
            long requestId,
            Duration timeout,
            boolean responseRequired) throws IOException {
        Request.Builder request = baseRequest(method, message)
                .post(RequestBody.create(
                        message.toString(), JSON_MEDIA_TYPE));
        Call call = httpClient.newCall(request.build());
        call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);

        try {
            return OkHttpCallExecutor.executeInterruptibly(
                    call,
                    response -> parseResponse(
                            response,
                            method,
                            requestId,
                            responseRequired));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("MCP HTTP 请求失败：" + method, error);
        }
    }

    private Request.Builder baseRequest(String method, JsonNode message) {
        Request.Builder request = new Request.Builder().url(endpoint);
        headers.forEach(request::header);
        request.header("Accept", ACCEPT)
                .header("Content-Type", "application/json")
                // 新版协议网关可直接根据 Header 路由，无需解析 JSON body。
                .header("Mcp-Method", method);

        if (sessionId != null && !sessionId.isBlank()) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null && !protocolVersion.isBlank()) {
            request.header("MCP-Protocol-Version", protocolVersion);
        }
        if ("tools/call".equals(method)) {
            String toolName = message.path("params").path("name").asText();
            if (!toolName.isBlank()) request.header("Mcp-Name", toolName);
        }
        return request;
    }

    private JsonNode parseResponse(
            Response response,
            String method,
            long requestId,
            boolean responseRequired) throws IOException {
        if (!response.isSuccessful()) {
            throw httpError(response, method);
        }

        if ("initialize".equals(method)) {
            String assignedSession = response.header("Mcp-Session-Id");
            if (assignedSession != null && !assignedSession.isBlank()) {
                sessionId = assignedSession;
            }
        }

        if (!responseRequired) {
            // Notification 通常返回 202 且没有 body。
            return JSON.nullNode();
        }

        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("MCP HTTP 响应为空：" + method);
        }

        String contentType = response.header("Content-Type", "")
                .toLowerCase();
        if (contentType.contains("text/event-stream")) {
            return readSseResult(body, requestId, method);
        }
        if (contentType.contains("application/json")
                || contentType.isBlank()) {
            return resultFromEnvelope(
                    JSON.readTree(body.string()), requestId, method);
        }
        throw new IOException("MCP HTTP 响应类型不支持：" + contentType);
    }

    private static JsonNode readSseResult(
            ResponseBody body,
            long requestId,
            String method) throws IOException {
        try (BufferedReader reader = new BufferedReader(body.charStream())) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    JsonNode result = eventResult(data, requestId, method);
                    data.setLength(0);
                    if (result != null) return result;
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    String value = line.substring(5);
                    data.append(value.startsWith(" ")
                            ? value.substring(1) : value);
                }
            }

            JsonNode result = eventResult(data, requestId, method);
            if (result != null) return result;
        }
        throw new IOException("MCP SSE 流未返回请求响应：" + method);
    }

    private static JsonNode eventResult(
            StringBuilder data,
            long requestId,
            String method) throws IOException {
        if (data.isEmpty() || "[DONE]".contentEquals(data)) return null;
        JsonNode envelope = JSON.readTree(data.toString());
        if (!matchesId(envelope, requestId)) {
            // 进度通知或服务端通知不属于本次最终响应，继续读取。
            return null;
        }
        return resultFromEnvelope(envelope, requestId, method);
    }

    private static JsonNode resultFromEnvelope(
            JsonNode envelope,
            long requestId,
            String method) throws IOException {
        if (envelope == null || !matchesId(envelope, requestId)) {
            throw new IOException("MCP JSON-RPC 响应 ID 不匹配：" + method);
        }
        JsonNode error = envelope.get("error");
        if (error != null && !error.isNull()) {
            throw new IOException(
                    "JSON-RPC 错误 " + error.path("code").asInt(-1)
                            + "：" + error.path("message")
                            .asText("unknown"));
        }
        return envelope.path("result");
    }

    private static boolean matchesId(JsonNode envelope, long requestId) {
        JsonNode id = envelope == null ? null : envelope.get("id");
        return id != null && !id.isNull()
                && Long.toString(requestId).equals(id.asText());
    }

    private static IOException httpError(Response response, String method) {
        String suffix = response.code() == 404
                ? "，远程 MCP 会话可能已失效" : "";
        return new IOException(
                "MCP HTTP " + response.code() + "：" + method + suffix);
    }

    private static ObjectNode message(String method, JsonNode params) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null
                ? JSON.createObjectNode() : params);
        return message;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("MCP HTTP 传输已关闭");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        // 有状态服务端可通过 DELETE 主动释放会话；不支持时通常返回 405。
        if (sessionId != null && !sessionId.isBlank()) {
            Request.Builder request = new Request.Builder().url(endpoint);
            headers.forEach(request::header);
            request.delete()
                    .header("Accept", ACCEPT)
                    .header("Mcp-Session-Id", sessionId);
            if (protocolVersion != null && !protocolVersion.isBlank()) {
                request.header("MCP-Protocol-Version", protocolVersion);
            }
            try (Response ignored = httpClient.newCall(request.build())
                    .execute()) {
                // 远端会话关闭尽力执行，响应码不影响本地资源释放。
            } catch (IOException ignored) {
            }
        }

        if (ownsHttpClient) {
            httpClient.dispatcher().cancelAll();
            httpClient.connectionPool().evictAll();
            httpClient.dispatcher().executorService().shutdown();
        }
    }
}
