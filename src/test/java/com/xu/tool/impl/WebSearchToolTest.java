package com.xu.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRequireApiKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebSearchTool(null));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSearchTool(" "));
    }

    @Test
    void shouldExposeOptionalSiteFilter() {
        WebSearchTool tool = new WebSearchTool("test-key");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) tool.inputSchema().get("properties");
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("site"));
    }

    @Test
    void shouldReturnStructuredValidationErrorsWithoutNetwork() throws Exception {
        WebSearchTool tool = new WebSearchTool("test-key");
        String result = tool.execute(Map.of("query", " "));

        assertEquals("error", mapper.readTree(result).path("status").asText());
        assertEquals("INVALID_ARGUMENT",
                mapper.readTree(result).path("errorCode").asText());
    }
}
