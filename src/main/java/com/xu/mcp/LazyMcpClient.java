package com.xu.mcp;

/**
 * 线程安全的 MCP 懒加载器。
 *
 * <p>同一实例可被多个 Agent 共享。第一个进入 ensureStarted 的线程负责完整
 * 初始化，其他线程等待并复用结果；只有 Starter 成功返回后才发布 client，
 * 因此失败不会暴露半初始化对象。</p>
 */
public final class LazyMcpClient implements AutoCloseable {

    private final Starter starter;
    private McpClient client;
    private boolean closed;

    public LazyMcpClient(Starter starter) {
        if (starter == null) {
            throw new IllegalArgumentException("MCP starter 不能为空");
        }
        this.starter = starter;
    }

    public synchronized McpClient ensureStarted() throws Exception {
        if (closed) {
            throw new IllegalStateException("MCP 懒加载器已关闭");
        }
        if (client != null) {
            return client;
        }

        McpClient started = starter.start();
        client = started;
        return client;
    }

    public synchronized boolean isStarted() {
        return client != null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @FunctionalInterface
    public interface Starter {
        McpClient start() throws Exception;
    }
}
