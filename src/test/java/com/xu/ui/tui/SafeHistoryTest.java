package com.xu.ui.tui;

import com.xu.ui.SafeDisplay;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsOrdinaryPromptsAndCommands() {
        assertTrue(SafeHistory.isSafe("解释 src/main/java 的架构"));
        assertTrue(SafeHistory.isSafe("/plan 添加一个登录页面"));
    }

    @Test
    void rejectsCommonCredentialShapesAndRegisteredRuntimeSecrets() {
        assertFalse(SafeHistory.isSafe(
                "curl -H \"Authorization: Bearer abcdefghijklmnop\" /api"));
        assertFalse(SafeHistory.isSafe(
                "deploy --password correct-horse-battery-staple"));
        assertFalse(SafeHistory.isSafe(
                "DEEPSEEK_API_KEY=sk-abcdefghijklmnop"));

        String runtimeSecret = "runtime-secret-value-123456";
        SafeDisplay.registerSecret(runtimeSecret);
        assertFalse(SafeHistory.isSafe(
                "please inspect " + runtimeSecret));
    }

    @Test
    void removesSensitiveExistingLinesAndNeverSavesNewOnes()
            throws Exception {
        Path historyFile = tempDir.resolve("input_history");
        long timestamp = Instant.now().toEpochMilli();
        Files.writeString(
                historyFile,
                timestamp + ":ordinary old prompt\n"
                        + (timestamp + 1)
                        + ":DEEPSEEK_API_KEY=sk-abcdefghijklmnop\n",
                StandardCharsets.UTF_8);

        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream())
                .encoding(StandardCharsets.UTF_8)
                .dumb(true)
                .build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(new SafeHistory(historyFile))
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .build();
            reader.getHistory().attach(reader);
            reader.getHistory().load();

            assertEquals(1, reader.getHistory().size());
            assertEquals(
                    "ordinary old prompt",
                    reader.getHistory().get(reader.getHistory().first()));

            reader.getHistory().add(
                    "deploy --password new-secret-value");
            reader.getHistory().add("ordinary new prompt");
            reader.getHistory().save();
        }

        String persisted = Files.readString(
                historyFile, StandardCharsets.UTF_8);
        assertFalse(persisted.contains("abcdefghijklmnop"));
        assertFalse(persisted.contains("new-secret-value"));
        assertTrue(persisted.contains("ordinary old prompt"));
        assertTrue(persisted.contains("ordinary new prompt"));
    }
}
