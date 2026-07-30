package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * 通过子进程 stdin/stdout 发送 JSON-RPC 2.0 请求。
 * MCP 语义由上层客户端处理，这里只负责进程、请求 ID、响应匹配和超时。
 */
public final class StdioJsonRpcClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG =
            LoggerFactory.getLogger(StdioJsonRpcClient.class);

    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final AtomicLong requestIds = new AtomicLong(1);
    // reader 线程通过 id 找到正在等待该响应的调用线程。
    private final Map<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private volatile boolean closed;
    private boolean closeStarted;

    public StdioJsonRpcClient(List<String> command,
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

    /** 测试用：使用内存 Reader/Writer，不启动真实子进程。 */
    StdioJsonRpcClient(Reader stdout, Writer stdin) {
        this.process = null;
        this.stdout = stdout instanceof BufferedReader reader
                ? reader : new BufferedReader(stdout);
        this.stdin = stdin instanceof BufferedWriter writer
                ? writer : new BufferedWriter(stdin);
        startStdoutReader();
    }

    public JsonNode request(String method, JsonNode params, Duration timeout)
            throws IOException {
        if (closed) throw new IOException("JSON-RPC 客户端已关闭");

        long id = requestIds.getAndIncrement();
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null
                ? JSON.createObjectNode() : params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        // 必须先登记再发送，避免服务端响应太快而找不到对应请求。
        pending.put(id, future);

        try {
            send(message);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("JSON-RPC 请求超时：" + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("JSON-RPC 请求被中断：" + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) throw ioe;
            throw new IOException("JSON-RPC 请求失败：" + method, cause);
        } finally {
            pending.remove(id);
        }
    }

    public void notification(String method, JsonNode params)
            throws IOException {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null
                ? JSON.createObjectNode() : params);
        send(message);
    }

    private synchronized void send(JsonNode message) throws IOException {
        if (closed) throw new IOException("JSON-RPC 客户端已关闭");
        stdin.write(JSON.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    private void startStdoutReader() {
        // stdout 只能由一个线程顺序读取，再按 id 分发给不同请求。
        Thread thread = new Thread(() -> {
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (!line.isBlank()) {
                        handleMessage(JSON.readTree(line));
                    }
                }
                if (!closed) {
                    closed = true;
                    failPending(new IOException("MCP 子进程已退出"));
                }
            } catch (Exception e) {
                if (!closed) {
                    closed = true;
                    failPending(e);
                }
            }
        }, "mcp-stdout");
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
            }
        }, "mcp-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleMessage(JsonNode message) {
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) return; // 服务端通知，当前最小客户端不消费。

        CompletableFuture<JsonNode> future = pending.remove(id.asLong());
        if (future == null) return;

        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new IOException(
                    "JSON-RPC 错误 " + error.path("code").asInt(-1)
                            + "：" + error.path("message").asText("unknown")));
        } else {
            future.complete(message.path("result"));
        }
    }

    private void failPending(Throwable cause) {
        IOException failure = cause instanceof IOException ioe
                ? ioe : new IOException("MCP 连接已断开", cause);
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        failPending(new IOException("JSON-RPC 客户端已关闭"));
    }
}
