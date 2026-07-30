package com.xu.observability;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextAwareTasksTest {

    private InMemorySpanExporter exporter;
    private Tracing tracing;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = Tracing.from(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build());
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        MDC.clear();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        tracing.close();
    }

    @Test
    void shouldPropagateTraceAndCleanWorkerThread() throws Exception {
        String rootTraceId;
        String rootSpanId;
        String[] childIds;

        try (MdcScope ignored = MdcScope.put("task_id", "task_1");
             TraceScope root = tracing.start("agent.run")) {
            rootTraceId = root.traceId();
            rootSpanId = root.spanId();

            childIds = CompletableFuture.supplyAsync(
                    ContextAwareTasks.wrap(() -> {
                        assertEquals(rootTraceId, MDC.get("trace_id"));
                        assertEquals("task_1", MDC.get("task_id"));
                        try (TraceScope child = tracing.start("plan.task")) {
                            return new String[]{
                                    child.traceId(), child.spanId()
                            };
                        }
                    }),
                    executor).get(5, TimeUnit.SECONDS);
        }

        assertEquals(rootTraceId, childIds[0]);
        assertNotEquals(rootSpanId, childIds[1]);

        // 使用同一个单线程执行器验证上一个任务没有把 MDC 留在线程池中。
        assertNull(executor.submit(
                () -> MDC.get("trace_id")).get(5, TimeUnit.SECONDS));
        assertNull(executor.submit(
                () -> MDC.get("task_id")).get(5, TimeUnit.SECONDS));

        var childSpan = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("plan.task"))
                .findFirst()
                .orElseThrow();
        assertEquals(rootSpanId,
                childSpan.getParentSpanContext().getSpanId());
    }
}
