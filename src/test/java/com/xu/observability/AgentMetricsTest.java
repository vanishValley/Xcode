package com.xu.observability;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMetricsTest {

    @Test
    void shouldPublishTaskLlmTokenAndToolMetrics() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        try (SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build()) {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setMeterProvider(meterProvider)
                    .build();
            AgentMetrics metrics = new AgentMetrics(
                    sdk.getMeter("metrics-test"));

            metrics.recordTask("PLAN", "SUCCESS", 1200);
            metrics.recordLlm("test-model", "SUCCESS", 800, 100, 20);
            metrics.recordTool("local", "FAILED", "TIMEOUT", 400);

            Set<String> names = reader.collectAllMetrics().stream()
                    .map(data -> data.getName())
                    .collect(Collectors.toSet());
            assertTrue(names.contains("coding.task.count"));
            assertTrue(names.contains("coding.task.duration"));
            assertTrue(names.contains("gen_ai.client.operation.count"));
            assertTrue(names.contains("gen_ai.client.operation.duration"));
            assertTrue(names.contains("gen_ai.client.token.usage"));
            assertTrue(names.contains("agent.tool.call.count"));
            assertTrue(names.contains("agent.tool.call.duration"));
        }
    }
}
