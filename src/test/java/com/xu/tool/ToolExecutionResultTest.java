package com.xu.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionResultTest {

    @Test
    void shouldClassifyObviousLegacyFailures() {
        assertFalse(ToolExecutionResult.fromLegacyText(
                "文件不存在：missing.txt").success());
        assertFalse(ToolExecutionResult.fromLegacyText(
                "{\"status\":\"error\",\"message\":\"boom\"}").success());
        assertFalse(ToolExecutionResult.fromLegacyText(
                "MCP_TOOL_ERROR\nboom").success());
    }

    @Test
    void shouldKeepNormalAndNoResultResponsesSuccessful() {
        assertTrue(ToolExecutionResult.fromLegacyText("ok").success());
        assertTrue(ToolExecutionResult.fromLegacyText(
                "{\"status\":\"no_results\",\"results\":[]}").success());
    }
}
