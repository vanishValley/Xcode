package com.xu.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.xu.tool.ToolExecutionResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeDisplayTest {

    @Test
    void shouldRedactNestedSecretsBeforeCreatingEvent() {
        String secret = String.join(
                "", "gh", "p_", "abcdefghijklmnopqrstuvwxyz123456");
        Map<String, Object> safe = SafeDisplay.arguments(Map.of(
                "apiKey", secret,
                "nested", Map.of(
                        "Authorization", "Bearer abc.def.ghi",
                        "items", List.of("safe", secret)),
                "command", "DEEPSEEK_API_KEY=" + secret + " mvn test",
                "content", "secret file body"));

        String display = safe.toString();
        assertFalse(display.contains(secret));
        assertFalse(display.contains("abc.def.ghi"));
        assertFalse(display.contains("secret file body"));
        assertTrue(display.contains("hidden"));
    }

    @Test
    void shouldRedactNeutralSecretsInEnvCommandsFlagsAndBasicAuth() {
        String secret = String.join(
                "", "neutral", "-secret", "-value", "-7391");
        String unsafe = String.join(
                "\n",
                "DEEPSEEK_API_KEY=" + secret + " mvn test",
                "WSA_API_KEY='" + secret + "'",
                "AWS_SECRET_ACCESS_KEY=\"" + secret + "\"",
                "tool --password " + secret,
                "https://user:" + secret + "@example.com/repo");

        String redacted = SafeDisplay.redact(unsafe);

        assertFalse(redacted.contains(secret));
        assertTrue(redacted.contains("DEEPSEEK_API_KEY="));
        assertTrue(redacted.contains("example.com/repo"));
    }

    @Test
    void shouldRedactAnExactlyRegisteredRuntimeCredential() {
        String secret = String.join(
                "", "runtime", "-opaque", "-credential", "-9127");
        SafeDisplay.registerSecret(secret);

        String redacted = SafeDisplay.redact(
                "model echoed " + secret + " without a key name");

        assertFalse(redacted.contains(secret));
    }

    @Test
    void shouldRemoveTerminalAndBidiControlCharacters() {
        String unsafe = "\u001B[31mred\u001B[0m\u0007\r"
                + "\u202Espoof\nsafe";

        String clean = SafeDisplay.redact(unsafe);

        assertFalse(clean.contains("\u001B"));
        assertFalse(clean.contains("\u0007"));
        assertFalse(clean.contains("\r"));
        assertFalse(clean.contains("\u202E"));
        assertTrue(clean.contains("red"));
        assertTrue(clean.contains("\nsafe"));
    }

    @Test
    void truncationShouldNotSplitEmojiSurrogatePair() {
        String truncated = SafeDisplay.text("😀".repeat(400));

        for (int index = 0; index < truncated.length(); index++) {
            char value = truncated.charAt(index);
            if (Character.isHighSurrogate(value)) {
                assertTrue(index + 1 < truncated.length());
                assertTrue(Character.isLowSurrogate(
                        truncated.charAt(++index)));
            } else {
                assertFalse(Character.isLowSurrogate(value));
            }
        }
    }

    @Test
    void streamingSanitizerShouldCatchSecretSplitAcrossChunks() {
        List<String> output = new ArrayList<>();
        StreamingDisplaySanitizer sanitizer =
                new StreamingDisplaySanitizer(output::add);

        sanitizer.accept("token=super-");
        sanitizer.accept("secret-value\nnext");
        sanitizer.flush();

        String displayed = String.join("", output);
        assertFalse(displayed.contains("super-secret-value"));
        assertTrue(displayed.contains("token=••••"));
        assertTrue(displayed.contains("next"));
    }

    @Test
    void immutableEventsShouldNeverRetainRawResultOrAssistantSecret() {
        String secret = String.join("", "gh", "p_", "event-secret-1234567890");
        UiEvent.ToolCompleted tool = new UiEvent.ToolCompleted(
                "main",
                "call",
                "read_file",
                Map.of("api_key", secret),
                ToolExecutionResult.success(secret),
                1);
        UiEvent.AssistantCompleted assistant =
                new UiEvent.AssistantCompleted(
                        "main",
                        "token=" + secret,
                        "SUCCESS",
                        1,
                        1,
                        0,
                        0,
                        0,
                        0,
                        1,
                        false);

        assertFalse(tool.toString().contains(secret));
        assertFalse(assistant.toString().contains(secret));
        assertEquals("", tool.result().content());
    }
}
