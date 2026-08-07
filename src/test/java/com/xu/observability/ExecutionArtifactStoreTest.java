package com.xu.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionArtifactStoreTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearConfiguration() {
        System.clearProperty("xcode.artifact.mode");
        System.clearProperty("xcode.artifact.entry.max_bytes");
        System.clearProperty("xcode.artifact.trace.max_bytes");
        System.clearProperty("xcode.artifact.retention_days");
    }

    @Test
    void shouldDiscardSuccessfulTraceInFailureMode() throws Exception {
        System.setProperty("xcode.artifact.mode", "failure");
        ExecutionArtifactStore store =
                ExecutionArtifactStore.create(tempDir);

        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Tracer tracer = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .build()
                    .getTracer("artifact-test");
            Span span = tracer.spanBuilder("coding.task").startSpan();
            String traceId = span.getSpanContext().getTraceId();
            try (Scope ignored = span.makeCurrent()) {
                store.beginTrace(traceId, "REACT", "hello");
                try (var operation = store.beginOperation(
                        "llm", "test-model", "request")) {
                    operation.success("response");
                }
            } finally {
                span.end();
            }

            store.completeTrace(traceId, "SUCCESS", false);
            assertFalse(Files.exists(
                    tempDir.resolve("staging").resolve(traceId)));
            assertFalse(Files.exists(tempDir.resolve("failures")));
        }
    }

    @Test
    void shouldRetainBoundedAndRedactedFailedTrace() throws Exception {
        System.setProperty("xcode.artifact.mode", "failure");
        System.setProperty("xcode.artifact.entry.max_bytes", "64");
        ExecutionArtifactStore store =
                ExecutionArtifactStore.create(tempDir);

        String secret = "artifact-secret-value";
        com.xu.ui.SafeDisplay.registerSecret(secret);
        String traceId;
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Tracer tracer = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .build()
                    .getTracer("artifact-test");
            Span span = tracer.spanBuilder("coding.task").startSpan();
            traceId = span.getSpanContext().getTraceId();
            try (Scope ignored = span.makeCurrent()) {
                store.beginTrace(traceId, "PLAN", "input " + secret);
                try (var operation = store.beginOperation(
                        "tool", "execute_command", "x".repeat(200))) {
                    operation.failure("failed " + secret);
                }
            } finally {
                span.end();
            }
        }

        store.completeTrace(traceId, "FAILED", false);
        Path retained = tempDir.resolve("failures")
                .resolve(LocalDate.now().toString())
                .resolve(traceId);
        assertTrue(Files.isDirectory(retained));
        try (var files = Files.walk(retained)) {
            String contents = files.filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    })
                    .reduce("", (left, right) -> left + right);
            assertFalse(contents.contains(secret));
            assertTrue(contents.contains("[truncated]"));
        }
    }

    @Test
    void shouldRemoveExpiredDateBucketsOnStartup() throws Exception {
        System.setProperty("xcode.artifact.mode", "failure");
        System.setProperty("xcode.artifact.retention_days", "7");
        Path expired = tempDir.resolve("failures")
                .resolve(LocalDate.now().minusDays(8).toString())
                .resolve("old-trace");
        Files.createDirectories(expired);
        Files.writeString(expired.resolve("payload.txt"), "old");

        ExecutionArtifactStore.create(tempDir);

        assertFalse(Files.exists(expired));
    }
}
