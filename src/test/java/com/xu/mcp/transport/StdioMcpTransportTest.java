package com.xu.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StdioMcpTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldMatchAResponseToItsRequestId() throws Exception {
        PipedWriter serverOutput = new PipedWriter();
        PipedReader clientOutput = new PipedReader(serverOutput);
        PipedWriter clientInput = new PipedWriter();
        PipedReader serverInput = new PipedReader(clientInput);
        AtomicReference<JsonNode> received = new AtomicReference<>();

        Thread fakeServer = new Thread(() -> {
            try {
                BufferedReader input = new BufferedReader(serverInput);
                BufferedWriter output = new BufferedWriter(serverOutput);
                JsonNode request = JSON.readTree(input.readLine());
                received.set(request);

                output.write("""
                        {"jsonrpc":"2.0","id":%d,"result":{"value":"ok"}}
                        """.formatted(
                        request.path("id").asLong()).strip());
                output.newLine();
                output.flush();
            } catch (Exception ignored) {
                // 客户端关闭管道后，假服务端自然结束。
            }
        }, "fake-stdio-mcp-server");
        fakeServer.setDaemon(true);
        fakeServer.start();

        try (StdioMcpTransport transport =
                     new StdioMcpTransport(clientOutput, clientInput)) {
            JsonNode result = transport.request(
                    "demo/echo",
                    JSON.createObjectNode().put("text", "hello"),
                    Duration.ofSeconds(2));

            assertEquals("ok", result.path("value").asText());
            assertEquals("2.0",
                    received.get().path("jsonrpc").asText());
            assertEquals("demo/echo",
                    received.get().path("method").asText());
            assertFalse(received.get().path("id").isMissingNode());
        }
    }
}
