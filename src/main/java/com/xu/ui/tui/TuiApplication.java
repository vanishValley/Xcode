package com.xu.ui.tui;

import com.xu.cli.CommandProcessor;
import com.xu.hitl.ApprovalResult;
import com.xu.plan.PlanStore;
import com.xu.skill.SkillRegistry;
import com.xu.ui.QueueUiEventSink;
import com.xu.ui.TuiHitlHandler;
import com.xu.ui.UiEvent;
import com.xu.util.CancellationToken;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inline JLine application with one active Agent run and one terminal writer.
 */
public final class TuiApplication implements AutoCloseable {

    private static final long TICK_MILLIS = 80L;

    private final Terminal terminal;
    private final QueueUiEventSink events;
    private final TuiHitlHandler hitl;
    private final CommandProcessor commands;
    private final Path projectRoot;
    private final int toolCount;
    private final int skillCount;
    private final LineReader reader;
    private final TuiRenderer renderer;
    private final ExecutorService agentExecutor;
    private final Runnable cancelActiveRequests;
    private final CancellationToken cancellation;
    private final Object activeRunLock = new Object();
    private final AtomicReference<Future<?>> activeFuture =
            new AtomicReference<>();
    private final AtomicReference<Thread> activeThread =
            new AtomicReference<>();
    private final AtomicReference<CompletableFuture<CommandProcessor.Result>>
            activeOutcome = new AtomicReference<>();
    private final AtomicBoolean activeTaskBodyStarted =
            new AtomicBoolean();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Terminal.SignalHandler previousInterruptHandler;
    private final Attributes originalTerminalAttributes;

    public TuiApplication(
            Terminal terminal,
            QueueUiEventSink events,
            TuiHitlHandler hitl,
            CommandProcessor commands,
            SkillRegistry skills,
            Path projectRoot,
            Path historyFile,
            int toolCount,
            int skillCount,
            CancellationToken cancellation,
            Runnable cancelActiveRequests) throws IOException {
        this.terminal = terminal;
        this.events = events;
        this.hitl = hitl;
        this.commands = commands;
        this.projectRoot = projectRoot;
        this.toolCount = toolCount;
        this.skillCount = skillCount;
        this.cancellation = cancellation == null
                ? new CancellationToken() : cancellation;
        this.cancelActiveRequests = cancelActiveRequests == null
                ? () -> { } : cancelActiveRequests;

        if (historyFile.getParent() != null) {
            Files.createDirectories(historyFile.getParent());
        }
        DefaultParser parser = new DefaultParser()
                .eofOnUnclosedQuote(false)
                .eofOnEscapedNewLine(false);
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("xcode")
                .parser(parser)
                .completer(new CommandCompleter(skills))
                .highlighter(new InputHighlighter())
                .history(new SafeHistory(historyFile))
                .variable(LineReader.HISTORY_FILE, historyFile)
                .variable(LineReader.HISTORY_SIZE, 500)
                .variable(LineReader.HISTORY_FILE_SIZE, 1_000)
                .option(LineReader.Option.AUTO_FRESH_LINE, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                .option(LineReader.Option.HISTORY_BEEP, false)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        /*
         * LineReaderBuilder attaches a supplied History before applying all
         * variables on some JLine versions. Reload after construction so the
         * configured project-scoped file is actually read and sanitized.
         */
        this.reader.getHistory().attach(this.reader);
        this.reader.getHistory().load();
        this.reader.setAutosuggestion(LineReader.SuggestionType.HISTORY);
        bindMultilineInput();

        this.renderer = new TuiRenderer(
                terminal,
                reader,
                commands.model(),
                commands::hitlEnabled);
        this.agentExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xcode-agent");
            thread.setDaemon(true);
            return thread;
        });
        this.previousInterruptHandler = terminal.handle(
                Terminal.Signal.INT, ignored -> requestCancellation());
        /*
         * Keep echo disabled between readLine calls. Without this, type-ahead
         * during an Agent run is echoed through the status display and may be
         * consumed by a later approval prompt.
         */
        this.originalTerminalAttributes = terminal.enterRawMode();
    }

    public void run() {
        renderer.banner(projectRoot, toolCount, skillCount);
        drainAvailableEvents();
        handleUnfinishedPlan();

        while (!closed.get()) {
            renderer.hideStatus();
            try {
                String input = reader.readLine(
                        prompt(), rightPrompt(), (Character) null, null);
                if (input.isBlank()) {
                    continue;
                }
                if ("/history clear".equalsIgnoreCase(input.strip())) {
                    clearInputHistory();
                    continue;
                }
                CommandProcessor.Result result =
                        executeAsync(() -> commands.execute(input));
                renderer.renderResult(result);
                if (result.exit()) {
                    break;
                }
            } catch (UserInterruptException ignored) {
                renderer.renderResult(CommandProcessor.Result.of(
                        CommandProcessor.Kind.INFO,
                        "已清空当前输入。"));
            } catch (EndOfFileException ignored) {
                renderer.renderResult(new CommandProcessor.Result(
                        true,
                        CommandProcessor.Kind.INFO,
                        "再见！",
                        false));
                break;
            } catch (RuntimeException error) {
                renderer.renderResult(CommandProcessor.Result.of(
                        CommandProcessor.Kind.ERROR,
                        "界面处理失败："
                                + com.xu.ui.SafeDisplay.errorPreview(
                                safeMessage(error))));
            }
        }
    }

    private void clearInputHistory() {
        try {
            reader.getHistory().purge();
            renderer.renderResult(CommandProcessor.Result.of(
                    CommandProcessor.Kind.SUCCESS,
                    "输入历史已从内存和磁盘清空。"));
        } catch (IOException error) {
            renderer.renderResult(CommandProcessor.Result.of(
                    CommandProcessor.Kind.ERROR,
                    "清空输入历史失败："
                            + com.xu.ui.SafeDisplay.errorPreview(
                            safeMessage(error))));
        }
    }

    private void handleUnfinishedPlan() {
        Optional<PlanStore.Checkpoint> unfinished =
                commands.unfinishedPlan();
        if (unfinished.isEmpty()) {
            return;
        }
        PlanStore.Checkpoint checkpoint = unfinished.get();
        renderer.renderResult(CommandProcessor.Result.of(
                CommandProcessor.Kind.WARNING,
                commands.unfinishedPlanSummary(checkpoint)));
        try {
            String choice = readWithoutHistory(
                    approvalPrompt("r 续跑 / d 丢弃 / Enter 稍后"));
            if ("r".equalsIgnoreCase(choice.strip())) {
                renderer.renderResult(executeAsync(
                        () -> commands.resume(checkpoint)));
            } else if ("d".equalsIgnoreCase(choice.strip())) {
                renderer.renderResult(commands.discardUnfinishedPlan());
            } else {
                renderer.renderResult(CommandProcessor.Result.of(
                        CommandProcessor.Kind.INFO,
                        "计划存档已保留。"));
            }
        } catch (UserInterruptException | EndOfFileException ignored) {
            renderer.renderResult(CommandProcessor.Result.of(
                    CommandProcessor.Kind.INFO,
                    "计划存档已保留。"));
        }
    }

    private CommandProcessor.Result executeAsync(
            Callable<CommandProcessor.Result> command) {
        cancellationRequested.set(false);
        cancellation.beginRun();
        hitl.beginRun();
        CompletableFuture<CommandProcessor.Result> outcome =
                new CompletableFuture<>();
        FutureTask<Void> scheduled = new FutureTask<>(() -> {
            cancellation.bindCurrentRun();
            activeTaskBodyStarted.set(true);
            activeThread.set(Thread.currentThread());
            try {
                if (cancellationRequested.get()
                        || Thread.currentThread().isInterrupted()) {
                    outcome.complete(cancelledResult());
                } else {
                    outcome.complete(command.call());
                }
            } catch (Throwable error) {
                outcome.complete(CommandProcessor.Result.of(
                        cancellationRequested.get()
                                ? CommandProcessor.Kind.WARNING
                                : CommandProcessor.Kind.ERROR,
                        cancellationRequested.get()
                                ? "当前任务已取消。"
                                : "执行失败：" + safeMessage(error)));
            } finally {
                activeThread.set(null);
            }
            return null;
        });
        synchronized (activeRunLock) {
            activeTaskBodyStarted.set(false);
            activeOutcome.set(outcome);
            activeFuture.set(scheduled);
            agentExecutor.execute(scheduled);
        }
        renderer.showStatus();
        long cancellationStartedAt = 0L;

        try {
            while (!outcome.isDone() || !events.isEmpty()) {
                UiEvent event = events.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    handleEvent(event);
                    drainAvailableEvents();
                }
                renderer.tick();
                if (cancellationRequested.get() && !outcome.isDone()) {
                    if (cancellationStartedAt == 0L) {
                        cancellationStartedAt = System.nanoTime();
                        renderer.cancellationRequested();
                    } else if (System.nanoTime() - cancellationStartedAt
                            >= TimeUnit.SECONDS.toNanos(3)) {
                        /*
                         * Do not reopen the prompt while an uncooperative
                         * tool might still mutate state. End the application;
                         * the executor thread is daemonized as a final guard.
                         */
                        closed.set(true);
                        outcome.complete(CommandProcessor.Result.of(
                                CommandProcessor.Kind.WARNING,
                                "任务未能及时停止，已安全退出交互界面。"));
                    }
                }
            }
            drainAvailableEvents();
            return outcome.getNow(CommandProcessor.Result.of(
                    CommandProcessor.Kind.ERROR,
                    "任务未返回结果。"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            requestCancellation();
            return CommandProcessor.Result.of(
                    CommandProcessor.Kind.WARNING,
                    "当前任务已取消。");
        } finally {
            if (!outcome.isDone()) {
                requestCancellation();
                try {
                    outcome.get(3, TimeUnit.SECONDS);
                } catch (Exception didNotStop) {
                    /*
                     * Never reopen the prompt while an old run could still
                     * reach a mutating tool. close() will shut down resources.
                     */
                    closed.set(true);
                }
            }
            renderer.hideStatus();
            if (!closed.get() && discardPendingInput()) {
                renderer.renderResult(CommandProcessor.Result.of(
                        CommandProcessor.Kind.INFO,
                        "已丢弃任务运行期间的预输入，请在提示符重新输入。"));
            }
            synchronized (activeRunLock) {
                activeFuture.compareAndSet(scheduled, null);
                activeOutcome.compareAndSet(outcome, null);
            }
            if (!cancellation.isReusable()) {
                closed.set(true);
                renderer.renderResult(CommandProcessor.Result.of(
                        CommandProcessor.Kind.WARNING,
                        "检测到后台工作线程未安全结束，已停止接收新任务。"));
            }
        }
    }

    private void handleEvent(UiEvent event) {
        if (event instanceof UiEvent.ApprovalRequested approval) {
            handleApproval(approval);
        } else {
            renderer.accept(event);
        }
    }

    private void drainAvailableEvents() {
        UiEvent event;
        while ((event = events.poll()) != null) {
            handleEvent(event);
        }
    }

    private void handleApproval(UiEvent.ApprovalRequested request) {
        if (request.response().isDone()) {
            return;
        }
        renderer.hideStatus();
        if (discardPendingInput()) {
            renderer.renderResult(CommandProcessor.Result.of(
                    CommandProcessor.Kind.INFO,
                    "已丢弃审批面板出现前的预输入。"));
        }
        renderer.approval(request);
        ApprovalResult result;
        try {
            result = readApprovalDecision();
        } catch (UserInterruptException | EndOfFileException interrupted) {
            result = new ApprovalResult(
                    ApprovalResult.Type.REJECTED, "审批被取消");
            requestCancellation();
        }
        if (hitl.completeApproval(request, result)) {
            renderer.approvalResolved(result);
        }
        renderer.showStatus();
    }

    private ApprovalResult readApprovalDecision() {
        while (true) {
            String choice = readWithoutHistory(
                    approvalPrompt("选择")).strip().toLowerCase();
            switch (choice) {
                case "y", "yes":
                    return new ApprovalResult(
                            ApprovalResult.Type.APPROVED, null);
                case "a", "all":
                    return new ApprovalResult(
                            ApprovalResult.Type.APPROVED_ALL, null);
                case "s", "skip":
                    return new ApprovalResult(
                            ApprovalResult.Type.SKIPPED, null);
                case "n", "no":
                    return rejectionWithOptionalReason();
                case "":
                    return new ApprovalResult(
                            ApprovalResult.Type.REJECTED, "用户拒绝");
                default:
                    renderer.renderResult(CommandProcessor.Result.of(
                            CommandProcessor.Kind.WARNING,
                            "请输入 y、a、s 或 n；直接回车表示拒绝。"));
            }
        }
    }

    private ApprovalResult rejectionWithOptionalReason() {
        try {
            String reason = readWithoutHistory(
                    approvalPrompt("拒绝原因（可留空）")).strip();
            return new ApprovalResult(
                    ApprovalResult.Type.REJECTED,
                    reason.isBlank() ? "用户拒绝" : reason);
        } catch (UserInterruptException | EndOfFileException ignored) {
            return new ApprovalResult(
                    ApprovalResult.Type.REJECTED, "用户拒绝");
        }
    }

    private String readWithoutHistory(String prompt) {
        Object old = reader.getVariable(LineReader.DISABLE_HISTORY);
        reader.setVariable(LineReader.DISABLE_HISTORY, true);
        try {
            return reader.readLine(prompt);
        } finally {
            reader.setVariable(
                    LineReader.DISABLE_HISTORY,
                    old == null ? false : old);
        }
    }

    private void requestCancellation() {
        Future<?> future;
        Thread worker;
        CompletableFuture<CommandProcessor.Result> outcome;
        synchronized (activeRunLock) {
            future = activeFuture.get();
            worker = activeThread.get();
            outcome = activeOutcome.get();
            if (future == null && worker == null && outcome == null) {
                return;
            }
            if (!cancellationRequested.compareAndSet(false, true)) {
                return;
            }
        }
        try {
            cancellation.cancel();
            cancelActiveRequests.run();
        } catch (RuntimeException ignored) {
        }
        hitl.cancelPending("任务已取消");
        boolean futureCancelled =
                future != null && future.cancel(true);
        if (worker != null) {
            worker.interrupt();
        }
        boolean cancelledBeforeStart =
                futureCancelled && !activeTaskBodyStarted.get();
        if (cancelledBeforeStart && outcome != null) {
            outcome.complete(cancelledResult());
        }
    }

    private static CommandProcessor.Result cancelledResult() {
        return CommandProcessor.Result.of(
                CommandProcessor.Kind.WARNING,
                "当前任务已取消。");
    }

    private boolean discardPendingInput() {
        boolean discarded = false;
        try {
            while (terminal.reader().ready()) {
                if (terminal.reader().read() < 0) {
                    break;
                }
                discarded = true;
            }
        } catch (IOException ignored) {
            // Best effort; LineReader remains the only normal input owner.
        }
        return discarded;
    }

    private void bindMultilineInput() {
        String widgetName = "xcode-insert-newline";
        reader.getWidgets().put(widgetName, () -> {
            reader.getBuffer().write("\n");
            return true;
        });
        Reference reference = new Reference(widgetName);
        for (String keyMapName : new String[]{
                LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> keyMap =
                    reader.getKeyMaps().get(keyMapName);
            if (keyMap != null) {
                keyMap.bind(reference, KeyMap.alt('\r'));
                keyMap.bind(reference, KeyMap.ctrl('J'));
            }
        }
    }

    private String prompt() {
        return new AttributedStringBuilder()
                .styled(TuiTheme.PROMPT, "❯ ")
                .toAttributedString()
                .toAnsi(terminal);
    }

    private String rightPrompt() {
        return new AttributedStringBuilder()
                .styled(
                        TuiTheme.SECONDARY,
                        commands.model() + " · HITL "
                                + (commands.hitlEnabled() ? "on" : "off"))
                .toAttributedString()
                .toAnsi(terminal);
    }

    private String approvalPrompt(String label) {
        return new AttributedStringBuilder()
                .styled(TuiTheme.WARNING, "  " + label + " ❯ ")
                .toAttributedString()
                .toAnsi(terminal);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : com.xu.ui.SafeDisplay.errorPreview(message);
    }

    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        closed.set(true);
        requestCancellation();
        hitl.cancelPending("应用已退出");
        terminal.handle(Terminal.Signal.INT, previousInterruptHandler);
        agentExecutor.shutdownNow();
        try {
            agentExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            reader.getHistory().save();
        } catch (IOException ignored) {
        }
        renderer.close();
        try {
            terminal.setAttributes(originalTerminalAttributes);
        } catch (RuntimeException ignored) {
        }
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
    }
}
