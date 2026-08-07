package com.xu.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xu.mcp.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于子进程 stdin/stdout 的 MCP 传输。
 *
 * <p>一个后台线程顺序读取 stdout，再按 JSON-RPC request id 把响应分发给
 * 不同调用线程；stdin 写入使用同步锁，防止并发请求的 JSON 文本互相穿插。</p>
 */
public final class StdioMcpTransport implements McpTransport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG =
            LoggerFactory.getLogger(StdioMcpTransport.class);

    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final AtomicLong requestIds = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private volatile boolean closed;
    private boolean closeStarted;

    public StdioMcpTransport(
            List<String> command,
            Map<String, String> environment,
            Path workingDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        if (environment != null) {
            builder.environment().putAll(environment);
        }

        process = builder.start();
        stdout = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));

        startStdoutReader();
        startStderrReader();
    }

    /** 测试入口：使用内存管道替代真实子进程。 */
    StdioMcpTransport(Reader stdout, Writer stdin) {
        this.process = null;
        this.stdout = stdout instanceof BufferedReader reader
                ? reader : new BufferedReader(stdout);
        this.stdin = stdin instanceof BufferedWriter writer
                ? writer : new BufferedWriter(stdin);
        startStdoutReader();
    }

    @Override
    public JsonNode request(String method, JsonNode params, Duration timeout)
            throws IOException {
        if (closed) throw new IOException("MCP stdio 传输已关闭");

        long id = requestIds.getAndIncrement();
        ObjectNode message = message(method, params);
        message.put("id", id);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        // 先登记再发送，避免服务端响应太快而找不到等待者。
        pending.put(id, future);
        try {
            send(message);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutError) {
            throw new IOException("MCP 请求超时：" + method, timeoutError);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException cancelled =
                    new InterruptedIOException("MCP 请求被中断：" + method);
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("MCP 请求失败：" + method, cause);
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void notification(String method, JsonNode params)
            throws IOException {
        send(message(method, params));
    }

    private static ObjectNode message(String method, JsonNode params) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null
                ? JSON.createObjectNode() : params);
        return message;
    }

    private synchronized void send(JsonNode message) throws IOException {
        if (closed) throw new IOException("MCP stdio 传输已关闭");
        stdin.write(JSON.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (!line.isBlank()) {
                        handleMessage(JSON.readTree(line));
                    }
                }
                failIfUnexpectedClose(new IOException("MCP 子进程已退出"));
            } catch (Exception error) {
                failIfUnexpectedClose(error);
            }
        }, "mcp-stdio-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader stderr = new BufferedReader(
                    new InputStreamReader(
                            process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    LOG.debug("MCP: {}", line);
                }
            } catch (IOException ignored) {
                // close() 关闭流时会结束该线程，无需额外处理。
            }
        }, "mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleMessage(JsonNode message) {
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return; // 当前客户端暂不消费服务端主动通知。
        }

        CompletableFuture<JsonNode> future = pending.remove(id.asLong());
        if (future == null) return;

        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(jsonRpcError(error));
        } else {
            future.complete(message.path("result"));
        }
    }

    private static IOException jsonRpcError(JsonNode error) {
        return new IOException(
                "JSON-RPC 错误 " + error.path("code").asInt(-1)
                        + "：" + error.path("message").asText("unknown"));
    }

    private void failIfUnexpectedClose(Throwable cause) {
        if (!closed) {
            closed = true;
            failPending(cause);
        }
    }

    private void failPending(Throwable cause) {
        IOException failure = cause instanceof IOException io
                ? io : new IOException("MCP 连接已断开", cause);
        pending.values().forEach(
                future -> future.completeExceptionally(failure));
        pending.clear();
    }

    @Override
    public synchronized void close() {
        if (closeStarted) return;
        closeStarted = true;
        closed = true;

        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            stdout.close();
        } catch (IOException ignored) {
        }

        if (process != null) {
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        failPending(new IOException("MCP stdio 传输已关闭"));
    }
}
