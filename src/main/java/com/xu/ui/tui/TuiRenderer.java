package com.xu.ui.tui;

import com.xu.cli.CommandProcessor;
import com.xu.hitl.ApprovalResult;
import com.xu.plan.Task;
import com.xu.ui.PlainUiEventSink;
import com.xu.ui.SafeDisplay;
import com.xu.ui.UiEvent;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * The only component allowed to write the rich terminal.
 *
 * <p>It keeps durable information in scrollback and transient activity in the
 * bottom status line.</p>
 */
final class TuiRenderer implements AutoCloseable {

    private static final String[] SPINNER =
            {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final Terminal terminal;
    private final LineReader reader;
    private final Status status;
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    private final String model;
    private final BooleanSupplier hitlEnabled;
    private final StringBuilder streamBuffer = new StringBuilder();
    private boolean streamStarted;
    private boolean streamCodeBlock;
    private String activity = "Ready";
    private long activityStartedAt = System.nanoTime();
    private int spinnerFrame;
    private boolean statusVisible;
    private int lastStatusWidth = -1;
    private int lastStatusHeight = -1;

    TuiRenderer(
            Terminal terminal,
            LineReader reader,
            String model,
            BooleanSupplier hitlEnabled) {
        this.terminal = terminal;
        this.reader = reader;
        this.model = model;
        this.hitlEnabled = hitlEnabled;
        this.status = Status.getStatus(terminal);
        this.status.setBorder(false);
    }

    void banner(
            Path projectRoot,
            int toolCount,
            int skillCount) {
        int terminalWidth = Math.max(1, terminal.getWidth());
        if (terminalWidth < 32) {
            print(fit(new AttributedStringBuilder()
                    .styled(TuiTheme.BRAND, "Xcode Agent")
                    .styled(TuiTheme.SECONDARY, " · ")
                    .append(model)
                    .toAttributedString(), terminalWidth));
            print(fit(new AttributedStringBuilder()
                    .styled(
                            hitlEnabled.getAsBoolean()
                                    ? TuiTheme.SUCCESS
                                    : TuiTheme.WARNING,
                            hitlEnabled.getAsBoolean()
                                    ? "HITL on" : "HITL off")
                    .styled(TuiTheme.SECONDARY, " · /help · Ctrl+C")
                    .toAttributedString(), terminalWidth));
            blank();
            return;
        }
        int width = Math.min(72, terminalWidth - 2);
        String shownModel = fitPlain(
                model,
                width >= 40
                        ? Math.max(8, width / 2)
                        : Math.max(8, width - 4));
        print(new AttributedStringBuilder()
                .styled(TuiTheme.BRAND, "╭─ Xcode Agent ")
                .styled(TuiTheme.SECONDARY, "─".repeat(
                        Math.max(1, width - 15)))
                .toAttributedString());
        print(new AttributedStringBuilder()
                .styled(TuiTheme.SECONDARY, "│ ")
                .styled(TuiTheme.BRAND, shownModel)
                .styled(
                        TuiTheme.SECONDARY,
                        width >= 40 ? "  ·  " : "")
                .append(width >= 40
                        ? shortenPath(
                                projectRoot,
                                width - displayWidth(shownModel) - 8)
                        : "")
                .toAttributedString());
        print(new AttributedStringBuilder()
                .styled(TuiTheme.SECONDARY, "│ ")
                .append(width >= 40
                        ? toolCount + " tools  ·  " + skillCount
                        + " skills  ·  "
                        : "")
                .styled(
                        hitlEnabled.getAsBoolean()
                                ? TuiTheme.SUCCESS : TuiTheme.WARNING,
                        hitlEnabled.getAsBoolean() ? "HITL on" : "HITL off")
                .toAttributedString());
        print(new AttributedStringBuilder()
                .styled(TuiTheme.SECONDARY, "╰" + "─".repeat(width))
                .toAttributedString());
        print(fit(new AttributedStringBuilder()
                .styled(
                        TuiTheme.SECONDARY,
                        width >= 50
                                ? "  /help  ·  Ctrl+J 换行  ·  Ctrl+C 取消  ·  Ctrl+D 退出"
                                : "  /help  ·  Ctrl+C")
                .toAttributedString(), terminalWidth));
        blank();
    }

    void accept(UiEvent event) {
        if (event instanceof UiEvent.Notice notice) {
            renderNotice(notice);
        } else if (event instanceof UiEvent.SessionChanged session) {
            renderSession(session);
        } else if (event instanceof UiEvent.AgentChanged agent) {
            renderAgent(agent);
        } else if (event instanceof UiEvent.AssistantDelta delta) {
            if ("main".equals(delta.taskLabel())) {
                streamBuffer.append(delta.text());
            }
        } else if (event instanceof UiEvent.AssistantCompleted assistant) {
            renderAssistant(assistant);
        } else if (event instanceof UiEvent.ToolStarted tool) {
            flushAllStream();
            streamStarted = false;
            streamCodeBlock = false;
            activity(taskPrefix(tool.taskLabel())
                    + "Running " + friendlyTool(tool.toolName())
                    + target(tool.arguments()));
        } else if (event instanceof UiEvent.ToolCompleted tool) {
            renderTool(tool);
        } else if (event instanceof UiEvent.PlanChanged plan) {
            renderPlan(plan);
        }
    }

    void renderResult(CommandProcessor.Result result) {
        if (result == null || result.text().isBlank()
                || result.renderedByEvents()) {
            return;
        }
        switch (result.kind()) {
            case ASSISTANT -> {
                blank();
                markdown.render(result.text()).forEach(this::print);
                blank();
            }
            case SUCCESS -> line(
                    "✓", TuiTheme.SUCCESS, result.text(),
                    AttributedStyle.DEFAULT);
            case WARNING -> line(
                    "!", TuiTheme.WARNING, result.text(),
                    AttributedStyle.DEFAULT);
            case ERROR -> line(
                    "✗", TuiTheme.ERROR, result.text(),
                    AttributedStyle.DEFAULT);
            case INFO -> markdown.render(result.text()).forEach(this::print);
            case NONE -> {
            }
        }
    }

    void approval(UiEvent.ApprovalRequested request) {
        activity("Approval required · " + request.toolName());
        blank();
        line("◆", TuiTheme.WARNING, "需要确认", TuiTheme.WARNING);
        if (!"main".equals(request.taskLabel())) {
            approvalDetail("步骤", request.taskLabel(), TuiTheme.BRAND);
        }
        approvalDetail("工具", request.toolName(), AttributedStyle.DEFAULT);
        approvalDetail("风险", request.dangerLevel(), TuiTheme.WARNING);
        request.arguments().forEach((key, value) -> approvalDetail(
                key,
                SafeDisplay.text(String.valueOf(value)),
                AttributedStyle.DEFAULT));
        if (terminal.getWidth() < 64) {
            approvalChoice("[y]", "允许一次", TuiTheme.SUCCESS);
            approvalChoice(
                    "[a]",
                    "本会话始终允许此工具",
                    TuiTheme.WARNING);
            approvalChoice("[s]", "跳过", TuiTheme.SECONDARY);
            approvalChoice("[n/Enter]", "拒绝", TuiTheme.ERROR);
        } else {
            print(fit(new AttributedStringBuilder()
                    .styled(TuiTheme.SUCCESS, "  [y] ")
                    .append("允许一次  ")
                    .styled(TuiTheme.WARNING, "[a] ")
                    .append("本会话始终允许此工具  ")
                    .styled(TuiTheme.SECONDARY, "[s] ")
                    .append("跳过  ")
                    .styled(TuiTheme.ERROR, "[n/Enter] ")
                    .append("拒绝")
                    .toAttributedString(), terminal.getWidth()));
        }
    }

    void approvalResolved(ApprovalResult result) {
        String text = switch (result.type()) {
            case APPROVED -> "已允许一次";
            case APPROVED_ALL -> "本会话已放行该工具";
            case SKIPPED -> "已跳过";
            case REJECTED -> "已拒绝";
        };
        AttributedStyle style = result.isApproved()
                ? TuiTheme.SUCCESS : TuiTheme.WARNING;
        line(result.isApproved() ? "✓" : "○", style, text, style);
        activity("Working");
    }

    void cancellationRequested() {
        activity("Cancelling");
        line("○", TuiTheme.WARNING, "正在取消当前任务…", TuiTheme.WARNING);
    }

    void tick() {
        flushCompleteStreamLines();
        if (!statusVisible) {
            return;
        }
        long elapsed = Math.max(
                0L, (System.nanoTime() - activityStartedAt) / 1_000_000_000L);
        int width = terminal.getWidth();
        AttributedStringBuilder line = new AttributedStringBuilder()
                .styled(TuiTheme.BRAND, SPINNER[
                        spinnerFrame++ % SPINNER.length])
                .append(" ")
                .append(activity)
                .styled(TuiTheme.SECONDARY, " · " + elapsed + "s");
        if (width >= 70) {
            line.styled(TuiTheme.SECONDARY, "    " + model
                    + " · HITL " + (hitlEnabled.getAsBoolean() ? "on" : "off"));
        }
        try {
            int height = terminal.getHeight();
            if (width != lastStatusWidth || height != lastStatusHeight) {
                status.resize();
                lastStatusWidth = width;
                lastStatusHeight = height;
            }
            status.update(
                    List.of(fit(line.toAttributedString(), width)),
                    true);
        } catch (RuntimeException ignored) {
            statusVisible = false;
        }
    }

    void showStatus() {
        statusVisible = terminal.getHeight() >= 8;
        activityStartedAt = System.nanoTime();
        tick();
    }

    void hideStatus() {
        statusVisible = false;
        try {
            status.hide();
        } catch (RuntimeException ignored) {
        }
    }

    private void renderNotice(UiEvent.Notice notice) {
        AttributedStyle style = switch (notice.severity()) {
            case SUCCESS -> TuiTheme.SUCCESS;
            case WARNING -> TuiTheme.WARNING;
            case ERROR -> TuiTheme.ERROR;
            case INFO -> TuiTheme.SECONDARY;
        };
        String marker = switch (notice.severity()) {
            case SUCCESS -> "✓";
            case WARNING -> "!";
            case ERROR -> "✗";
            case INFO -> "i";
        };
        line(
                marker,
                style,
                SafeDisplay.redact(notice.message()),
                AttributedStyle.DEFAULT);
    }

    private void renderSession(UiEvent.SessionChanged session) {
        if (session.action() == UiEvent.SessionAction.CONTEXT_INJECTED) {
            return;
        }
        String suffix = session.messageCount() > 0
                ? " · " + session.messageCount() + " messages" : "";
        line(
                "○",
                TuiTheme.SECONDARY,
                session.message() + suffix,
                TuiTheme.SECONDARY);
    }

    private void renderAgent(UiEvent.AgentChanged agent) {
        if (!"main".equals(agent.taskLabel())) {
            return;
        }
        switch (agent.phase()) {
            case STARTED -> activity("Thinking");
            case TURN_STARTED -> activity(
                    "Thinking · turn " + agent.turn());
            case WAITING_FOR_MODEL -> activity(
                    "Waiting for model · turn " + agent.turn());
            case FAILED -> {
                flushAllStream();
                streamStarted = false;
                streamCodeBlock = false;
                line("✗", TuiTheme.ERROR, agent.message(), TuiTheme.ERROR);
                activity("Failed");
            }
            case CANCELLED -> {
                flushAllStream();
                streamStarted = false;
                streamCodeBlock = false;
                String message = agent.message() == null
                        || agent.message().isBlank()
                        ? "任务已取消" : agent.message();
                line(
                        "○",
                        TuiTheme.WARNING,
                        SafeDisplay.redact(message),
                        TuiTheme.WARNING);
                activity("Cancelled");
            }
            case COMPLETED -> activity("Done");
        }
    }

    private void renderAssistant(UiEvent.AssistantCompleted assistant) {
        if (!"main".equals(assistant.taskLabel())) {
            return;
        }
        if (assistant.streamed()) {
            flushAllStream();
        } else {
            blank();
            markdown.render(assistant.content()).forEach(this::print);
        }
        blank();
        String tokens = assistant.inputTokens() + assistant.outputTokens() > 0
                ? " · " + (assistant.inputTokens()
                + assistant.outputTokens()) + " tokens" : "";
        print(new AttributedStringBuilder()
                .styled(TuiTheme.SECONDARY, "  ─ ")
                .styled(
                        "SUCCESS".equals(assistant.outcome())
                                ? TuiTheme.SUCCESS : TuiTheme.WARNING,
                        "SUCCESS".equals(assistant.outcome())
                                ? "Done" : assistant.outcome())
                .styled(TuiTheme.SECONDARY,
                        " · " + assistant.turns() + " turns"
                                + " · " + assistant.toolCalls() + " tools"
                                + tokens + " · "
                                + PlainUiEventSink.formatDuration(
                                assistant.durationMillis()))
                .toAttributedString());
        streamStarted = false;
        streamCodeBlock = false;
    }

    private void renderTool(UiEvent.ToolCompleted tool) {
        boolean success = tool.result().success();
        String marker = success ? "✓" : "✗";
        AttributedStyle style = success
                ? TuiTheme.SUCCESS : TuiTheme.ERROR;
        String details = taskPrefix(tool.taskLabel())
                + friendlyTool(tool.toolName())
                + target(tool.arguments());
        String process = tool.result().exitCode() == null
                ? ""
                : " · exit " + tool.result().exitCode();
        print(new AttributedStringBuilder()
                .styled(style, "  " + marker + " ")
                .append(details)
                .styled(TuiTheme.SECONDARY,
                        process + " · " + PlainUiEventSink.formatDuration(
                                tool.durationMillis()))
                .toAttributedString());
        if (!success && tool.result().content() != null
                && !tool.result().content().isBlank()) {
            String preview = SafeDisplay.errorPreview(
                    tool.result().content());
            for (String line : preview.split("\\n", -1)) {
                print(new AttributedStringBuilder()
                        .styled(TuiTheme.ERROR, "    │ ")
                        .styled(TuiTheme.SECONDARY, line)
                        .toAttributedString());
            }
        }
        activity(success ? "Thinking" : "Recovering");
    }

    private void renderPlan(UiEvent.PlanChanged plan) {
        switch (plan.phase()) {
            case PLANNING -> activity("Planning");
            case PLAN_CREATED -> {
                blank();
                line("◆", TuiTheme.BRAND, plan.message(), TuiTheme.HEADING);
                for (UiEvent.PlanTaskView task : plan.tasks()) {
                    print(new AttributedStringBuilder()
                            .styled(TuiTheme.SECONDARY, "  ○ " + task.id() + "  ")
                            .append(task.description())
                            .toAttributedString());
                }
                activity("Executing plan");
            }
            case RESUMING -> {
                line("◆", TuiTheme.BRAND, plan.message(), TuiTheme.HEADING);
                activity("Resuming plan");
            }
            case ROUND_STARTED -> activity(
                    "Plan round " + plan.round());
            case TASK_STARTED -> print(new AttributedStringBuilder()
                    .styled(TuiTheme.BRAND, "  ● ")
                    .append(plan.taskId() + "  " + plan.message())
                    .toAttributedString());
            case TASK_COMPLETED -> {
                Task.Status taskStatus = plan.tasks().stream()
                        .filter(task -> task.id().equals(plan.taskId()))
                        .map(UiEvent.PlanTaskView::status)
                        .findFirst()
                        .orElse(Task.Status.COMPLETED);
                boolean failed = taskStatus == Task.Status.FAILED;
                print(new AttributedStringBuilder()
                        .styled(
                                failed ? TuiTheme.ERROR : TuiTheme.SUCCESS,
                                failed ? "  ✗ " : "  ✓ ")
                        .append(plan.taskId() + "  " + plan.message())
                        .toAttributedString());
            }
            case REPLANNING -> {
                line("↻", TuiTheme.WARNING, plan.message(), TuiTheme.WARNING);
                activity("Replanning");
            }
            case COMPLETED -> activity("Plan complete");
            case FAILED -> {
                line("✗", TuiTheme.ERROR, plan.message(), TuiTheme.ERROR);
                activity("Plan failed");
            }
            case CANCELLED -> {
                line("○", TuiTheme.WARNING, "计划已取消", TuiTheme.WARNING);
                activity("Plan cancelled");
            }
            case EXECUTING -> activity("Executing plan");
        }
    }

    private void flushCompleteStreamLines() {
        int lastNewline = streamBuffer.lastIndexOf("\n");
        if (lastNewline < 0) {
            return;
        }
        String complete = streamBuffer.substring(0, lastNewline);
        streamBuffer.delete(0, lastNewline + 1);
        printStreamChunk(complete);
    }

    private void flushAllStream() {
        if (streamBuffer.isEmpty()) {
            return;
        }
        String remaining = streamBuffer.toString();
        streamBuffer.setLength(0);
        printStreamChunk(remaining);
    }

    private void printStreamChunk(String value) {
        if (!streamStarted) {
            blank();
            streamStarted = true;
        }
        MarkdownRenderer.RenderResult rendered =
                markdown.render(value, streamCodeBlock);
        streamCodeBlock = rendered.codeBlock();
        rendered.lines().forEach(this::print);
    }

    private void activity(String value) {
        if (!value.equals(activity)) {
            activity = value;
            activityStartedAt = System.nanoTime();
        }
    }

    private static String friendlyTool(String toolName) {
        return switch (toolName) {
            case "read_file" -> "读取";
            case "write_file" -> "写入";
            case "list_dir" -> "浏览";
            case "glob_files" -> "搜索";
            case "execute_command" -> "执行";
            case "web_search" -> "搜索网页";
            case "web_fetch" -> "获取网页";
            case "load_skill" -> "加载 Skill";
            default -> toolName.startsWith("mcp__")
                    ? "MCP " + toolName.substring(5).replace("__", " / ")
                    : toolName;
        };
    }

    private static String taskPrefix(String taskLabel) {
        return taskLabel == null
                || taskLabel.isBlank()
                || "main".equals(taskLabel)
                ? "" : "[" + taskLabel + "] ";
    }

    private static String target(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> preferred = List.of(
                "path", "file_path", "pattern", "command",
                "query", "url", "name", "cwd");
        for (String key : preferred) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return "  " + SafeDisplay.text(String.valueOf(value));
            }
        }
        return "  " + arguments.entrySet().stream()
                .limit(2)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void line(
            String marker,
            AttributedStyle markerStyle,
            String text,
            AttributedStyle textStyle) {
        String safe = SafeDisplay.redact(text);
        String[] lines = safe.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            print(new AttributedStringBuilder()
                    .styled(
                            markerStyle,
                            index == 0 ? "  " + marker + " " : "    ")
                    .styled(textStyle, lines[index])
                    .toAttributedString());
        }
    }

    private void print(AttributedString value) {
        reader.printAbove(value);
    }

    private void approvalDetail(
            String label,
            String value,
            AttributedStyle valueStyle) {
        int width = Math.max(12, terminal.getWidth());
        String prefix = "  " + SafeDisplay.text(label) + "  ";
        AttributedString safeValue =
                new AttributedString(SafeDisplay.text(value));
        int offset = 0;
        int firstCapacity = Math.max(1, width - displayWidth(prefix));
        int firstEnd = Math.min(
                safeValue.columnLength(),
                offset + firstCapacity);
        print(fit(new AttributedStringBuilder()
                .styled(TuiTheme.SECONDARY, prefix)
                .styled(
                        valueStyle,
                        safeValue.columnSubSequence(offset, firstEnd)
                                .toString())
                .toAttributedString(), width));
        offset = firstEnd;
        int continuationCapacity = Math.max(1, width - 4);
        while (offset < safeValue.columnLength()) {
            int end = Math.min(
                    safeValue.columnLength(),
                    offset + continuationCapacity);
            print(new AttributedStringBuilder()
                    .styled(TuiTheme.SECONDARY, "    ")
                    .styled(
                            valueStyle,
                            safeValue.columnSubSequence(offset, end)
                                    .toString())
                    .toAttributedString());
            offset = end;
        }
    }

    private void approvalChoice(
            String key,
            String label,
            AttributedStyle keyStyle) {
        print(fit(new AttributedStringBuilder()
                .styled(keyStyle, "  " + key + " ")
                .append(label)
                .toAttributedString(), Math.max(1, terminal.getWidth())));
    }

    private void blank() {
        reader.printAbove("");
    }

    private static String shortenPath(Path path, int max) {
        String value = path.toString();
        if (max < 8) {
            return "…";
        }
        AttributedString attributed = new AttributedString(value);
        int columns = attributed.columnLength();
        if (columns <= max) {
            return value;
        }
        return "…" + attributed.columnSubSequence(
                columns - max + 1, columns);
    }

    private static int displayWidth(String value) {
        return new AttributedString(value == null ? "" : value)
                .columnLength();
    }

    private static String fitPlain(String value, int maxColumns) {
        AttributedString attributed =
                new AttributedString(value == null ? "" : value);
        if (attributed.columnLength() <= maxColumns) {
            return attributed.toString();
        }
        if (maxColumns <= 1) {
            return "…";
        }
        return attributed.columnSubSequence(
                0, maxColumns - 1) + "…";
    }

    private static AttributedString fit(
            AttributedString value,
            int maxColumns) {
        if (value.columnLength() <= maxColumns) {
            return value;
        }
        if (maxColumns <= 1) {
            return new AttributedString("…");
        }
        return new AttributedStringBuilder()
                .append(value.columnSubSequence(0, maxColumns - 1))
                .append("…")
                .toAttributedString();
    }

    @Override
    public void close() {
        flushAllStream();
        hideStatus();
        try {
            status.close();
        } catch (RuntimeException ignored) {
        }
        terminal.flush();
    }
}
