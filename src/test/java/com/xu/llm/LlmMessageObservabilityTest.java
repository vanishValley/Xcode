package com.xu.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMessageObservabilityTest {

    @Test
    void shouldNotSerializeLocalObservabilityFields() throws Exception {
        LlmClient.Message message =
                new LlmClient.Message("assistant", "done");
        message.inputTokens = 100;
        message.outputTokens = 20;
        message.totalTokens = 120;
        message.finishReason = "stop";

        String json = new ObjectMapper().writeValueAsString(message);

        assertTrue(json.contains("\"content\":\"done\""));
        assertFalse(json.contains("inputTokens"));
        assertFalse(json.contains("outputTokens"));
        assertFalse(json.contains("totalTokens"));
        assertFalse(json.contains("finishReason"));
    }
}
