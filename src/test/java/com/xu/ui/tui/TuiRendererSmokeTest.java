package com.xu.ui.tui;

import com.xu.tool.ToolExecutionResult;
import com.xu.ui.UiEvent;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.xu.hitl.ApprovalResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiRendererSmokeTest {

    @Test
    void shouldRenderCommonViewsAtNarrowAndWideTerminalSizes()
            throws Exception {
        for (int width : new int[]{20, 40, 80, 120}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Terminal terminal = TerminalBuilder.builder()
                    .system(false)
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .encoding(StandardCharsets.UTF_8)
                    .type("xterm-256color")
                    .size(new Size(width, 24))
                    .dumb(true)
                    .build()) {
                LineReader reader = LineReaderBuilder.builder()
                        .terminal(terminal)
                        .build();
                try (TuiRenderer renderer = new TuiRenderer(
                        terminal, reader, "deepseek-chat", () -> true)) {
                    renderer.banner(
                            Path.of("D:/workspace/demo"), 12, 4);
                    renderer.accept(new UiEvent.Notice(
                            UiEvent.Severity.WARNING, "warning"));
                    renderer.accept(new UiEvent.ToolCompleted(
                            "main",
                            "call_1",
                            "execute_command",
                            Map.of("command", "mvn test"),
                            ToolExecutionResult.command(
                                    "compile failed", 1, false),
                            1_250));
                    renderer.accept(new UiEvent.AssistantCompleted(
                            "main",
                            "## Result\n- **done**",
                            "SUCCESS",
                            2,
                            2,
                            1,
                            0,
                            10,
                            5,
                            2_000,
                            false));
                    renderer.approval(new UiEvent.ApprovalRequested(
                            1L,
                            "task_0",
                            "execute_command",
                            "高危 — 将在本机执行 Shell 命令",
                            Map.of(
                                    "command",
                                    "mvn -q -DskipTests=false test"),
                            new CompletableFuture<ApprovalResult>()));
                }
            }
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Xcode Agent"));
            assertTrue(rendered.contains("mvn test"));
            assertTrue(rendered.contains("Result"));
            assertTrue(rendered.contains("task_0"));
        }
    }
}
