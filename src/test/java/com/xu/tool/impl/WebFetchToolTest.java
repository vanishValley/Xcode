package com.xu.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldBlockPrivateMetadataAndCgnatAddresses() throws Exception {
        assertTrue(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("127.0.0.1")));
        assertTrue(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("10.0.0.1")));
        assertTrue(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("192.168.1.1")));
        assertTrue(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("169.254.169.254")));
        assertTrue(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("100.64.0.1")));
        assertTrue(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("fc00::1")));
    }

    @Test
    void shouldAllowPublicAddresses() throws Exception {
        assertFalse(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("8.8.8.8")));
        assertFalse(WebFetchTool.isBlockedAddress(
                InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void shouldReturnStructuredBlockedResultWithoutNetwork() throws Exception {
        WebFetchTool tool = new WebFetchTool();
        JsonNode result = mapper.readTree(tool.execute(
                Map.of("url", "http://127.0.0.1:8080/admin")));

        assertEquals("blocked", result.path("status").asText());
        assertEquals("UNSAFE_TARGET", result.path("errorCode").asText());
    }

    @Test
    void shouldRejectUnsupportedScheme() throws Exception {
        WebFetchTool tool = new WebFetchTool();
        JsonNode result = mapper.readTree(tool.execute(
                Map.of("url", "file:///etc/passwd")));

        assertEquals("blocked", result.path("status").asText());
        assertEquals("UNSAFE_URL", result.path("errorCode").asText());
    }
}
