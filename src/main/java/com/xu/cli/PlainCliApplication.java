package com.xu.cli;

import com.xu.plan.PlanStore;
import com.xu.ui.SafeDisplay;
import com.xu.util.CancellationToken;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.Scanner;

/** 面向管道、IDE 控制台和降级场景的阻塞式轻量 CLI。 */
public final class PlainCliApplication {

    private final Scanner scanner;
    private final PrintWriter writer;
    private final CommandProcessor commands;
    private final CancellationToken cancellation;

    public PlainCliApplication(
            Scanner scanner,
            PrintWriter writer,
            CommandProcessor commands) {
        this(scanner, writer, commands, new CancellationToken());
    }

    public PlainCliApplication(
            Scanner scanner,
            PrintWriter writer,
            CommandProcessor commands,
            CancellationToken cancellation) {
        this.scanner = scanner;
        this.writer = writer;
        this.commands = commands;
        this.cancellation = cancellation == null
                ? new CancellationToken() : cancellation;
    }

    public void run() {
        handleUnfinishedPlan();

        while (true) {
            writer.print("\n> ");
            writer.flush();
            if (!scanner.hasNextLine()) {
                writer.println();
                break;
            }
            if (!beginRun()) {
                break;
            }
            CommandProcessor.Result result =
                    commands.execute(scanner.nextLine());
            render(result);
            if (!cancellation.isReusable()) {
                writer.println(
                        "检测到后台工作线程未安全结束，已停止接收新任务。");
                break;
            }
            if (result.exit()) {
                break;
            }
        }
    }

    private void handleUnfinishedPlan() {
        Optional<PlanStore.Checkpoint> unfinished =
                commands.unfinishedPlan();
        if (unfinished.isEmpty()) {
            return;
        }
        PlanStore.Checkpoint checkpoint = unfinished.get();
        writer.println();
        writer.println(SafeDisplay.redact(
                commands.unfinishedPlanSummary(checkpoint)));
        writer.print("[r] 续跑  [d] 丢弃  [Enter] 稍后处理 > ");
        writer.flush();
        if (!scanner.hasNextLine()) {
            return;
        }
        String choice = scanner.nextLine().strip().toLowerCase();
        if ("r".equals(choice)) {
            if (beginRun()) {
                render(commands.resume(checkpoint));
            }
        } else if ("d".equals(choice)) {
            render(commands.discardUnfinishedPlan());
        } else {
            writer.println("计划存档已保留。");
        }
    }

    private void render(CommandProcessor.Result result) {
        if (!result.renderedByEvents() && !result.text().isBlank()) {
            writer.println(result.text());
            writer.flush();
        }
    }

    private boolean beginRun() {
        if (!cancellation.isReusable()) {
            writer.println(
                    "检测到后台工作线程未安全结束，已停止接收新任务。");
            return false;
        }
        cancellation.beginRun();
        cancellation.bindCurrentRun();
        return true;
    }
}
