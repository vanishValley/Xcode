package com.xu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.observability.Tracing;
import com.xu.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class LazyMcpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldStartOnlyOnceWhenAgentsInitializeConcurrently()
            throws Exception {
        AtomicInteger starts = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        LazyMcpClient lazy = new LazyMcpClient(() -> {
            starts.incrementAndGet();
            Thread.sleep(50);
            return connectEmpty(registry);
        });

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<McpClient>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                calls.add(lazy::ensureStarted);
            }
            List<Future<McpClient>> futures = pool.invokeAll(calls);
            McpClient first = futures.get(0).get();
            for (Future<McpClient> future : futures) {
                assertSame(first, future.get());
            }
        } finally {
            pool.shutdownNow();
            lazy.close();
        }

        assertEquals(1, starts.get());
        assertFalse(lazy.isStarted());
    }

    private static McpClient connectEmpty(ToolRegistry registry)
            throws Exception {
        return McpClient.connect(
                "test",
                "Test",
                new EmptyTransport(),
                Set.of(),
                false,
                registry,
                Tracing.noop());
    }

    private static final class EmptyTransport implements McpTransport {
        @Override
        public JsonNode request(
                String method,
                JsonNode params,
                Duration timeout) {
            if ("initialize".equals(method)) {
                return JSON.createObjectNode()
                        .put("protocolVersion", "2025-11-25");
            }
            var result = JSON.createObjectNode();
            result.putArray("tools");
            return result;
        }

        @Override
        public void notification(String method, JsonNode params) {
        }

        @Override
        public void close() {
        }
    }
}
