package com.xu.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamableHttpMcpTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldKeepSessionAndParseJsonAndSseResponses()
            throws Exception {
        AtomicReference<String> sessionHeader = new AtomicReference<>();
        AtomicReference<String> protocolHeader = new AtomicReference<>();
        AtomicBoolean deleteReceived = new AtomicBoolean();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(
                exchange,
                sessionHeader,
                protocolHeader,
                deleteReceived));
        server.start();

        String endpoint = "http://127.0.0.1:"
                + server.getAddress().getPort() + "/mcp";
        try (StreamableHttpMcpTransport transport =
                     new StreamableHttpMcpTransport(
                             endpoint, Map.of("X-Test", "yes"),
                             new OkHttpClient())) {
            JsonNode initialized = transport.request(
                    "initialize",
                    JSON.createObjectNode(),
                    Duration.ofSeconds(2));
            assertEquals("2025-11-25",
                    initialized.path("protocolVersion").asText());

            transport.setProtocolVersion("2025-11-25");
            transport.notification(
                    "notifications/initialized",
                    JSON.createObjectNode());

            JsonNode listed = transport.request(
                    "tools/list",
                    JSON.createObjectNode(),
                    Duration.ofSeconds(2));
            assertEquals("demo", listed.path("tools").get(0)
                    .path("name").asText());
            assertEquals("session-123", sessionHeader.get());
            assertEquals("2025-11-25", protocolHeader.get());
        } finally {
            server.stop(0);
        }
        assertTrue(deleteReceived.get());
    }

    private static void handle(
            HttpExchange exchange,
            AtomicReference<String> sessionHeader,
            AtomicReference<String> protocolHeader,
            AtomicBoolean deleteReceived) throws IOException {
        try (exchange) {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteReceived.set(true);
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (!"initialize".equals(method)) {
                sessionHeader.set(exchange.getRequestHeaders()
                        .getFirst("Mcp-Session-Id"));
                protocolHeader.set(exchange.getRequestHeaders()
                        .getFirst("MCP-Protocol-Version"));
            }

            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            if ("initialize".equals(method)) {
                response.putObject("result")
                        .put("protocolVersion", "2025-11-25");
                exchange.getResponseHeaders().add(
                        "Mcp-Session-Id", "session-123");
                sendJson(exchange, response.toString());
                return;
            }

            ObjectNode result = response.putObject("result");
            result.putArray("tools")
                    .addObject().put("name", "demo");
            String progress = "data: {\"jsonrpc\":\"2.0\","
                    + "\"method\":\"notifications/progress\"}\n\n";
            String body = progress + "data: " + response + "\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void sendJson(HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(
                "Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
