package com.xu.observability;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceScopeTest {

    private InMemorySpanExporter exporter;
    private Tracing tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        tracing = Tracing.from(sdk);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        tracing.close();
    }

    @Test
    void shouldCreateChildSpanAndRestoreParentMdc() {
        String rootTraceId;
        String rootSpanId;
        String childSpanId;

        try (TraceScope root = tracing.start("coding.task")) {
            rootTraceId = root.traceId();
            rootSpanId = root.spanId();
            assertFalse(rootTraceId.isBlank());
            assertEquals(rootTraceId, MDC.get("trace_id"));
            assertEquals(rootSpanId, MDC.get("span_id"));

            try (TraceScope child = tracing.start("agent.invoke")) {
                childSpanId = child.spanId();
                assertEquals(rootTraceId, child.traceId());
                assertNotEquals(rootSpanId, childSpanId);
                assertEquals(childSpanId, MDC.get("span_id"));
            }

            assertEquals(rootTraceId, MDC.get("trace_id"));
            assertEquals(rootSpanId, MDC.get("span_id"));
        }

        assertNull(MDC.get("trace_id"));
        assertNull(MDC.get("span_id"));
        assertEquals(2, exporter.getFinishedSpanItems().size());
    }

    @Test
    void shouldRecordErrorStatus() {
        try (TraceScope scope = tracing.start("llm.chat")) {
            scope.fail(new IllegalStateException("network failed"));
        }

        var span = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("exception", span.getEvents().get(0).getName());
    }

    @Test
    void noopTracingShouldNotOverwriteExistingMdc() {
        MDC.put("trace_id", "existing-trace");
        MDC.put("span_id", "existing-span");

        try (TraceScope ignored = Tracing.noop().start("ignored")) {
            assertEquals("existing-trace", MDC.get("trace_id"));
            assertEquals("existing-span", MDC.get("span_id"));
        }

        assertEquals("existing-trace", MDC.get("trace_id"));
        assertEquals("existing-span", MDC.get("span_id"));
    }
}
