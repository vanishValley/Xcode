package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.memory.LongTermMemory;
import com.xu.memory.LessonExtractor;
import com.xu.memory.MemoryManager;
import com.xu.observability.ContextAwareTasks;
import com.xu.observability.MdcScope;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.plan.ExecutionPlan;
import com.xu.plan.PlanStore;
import com.xu.plan.Planner;
import com.xu.plan.Task;
import com.xu.skill.SkillRegistry;
import com.xu.tool.ToolRegistry;
import com.xu.ui.UiEvent;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.*;

import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute Agent —— "先规划，再执行"。
 *
 * 和 ReAct Agent 的区别：
 *   ReAct:  拿到任务 → 边想边做（走一步看一步）
 *   Plan:   拿到任务 → 先拆成步骤 → 按依赖顺序逐个执行
 *
 * 完整流程：
 *   1. 调用 Planner，让 LLM 把用户需求拆成 Task 列表
 *   2. 展示计划给用户
 *   3. 循环：找出所有"就绪"的 Task → 为每个 Task 启动子 Agent → 执行 → 记录结果
 *   4. 汇总所有结果，返回最终报告
 *
 * 为什么不直接在每一步里调工具？
 *   每个 Task 内部是一个独立的 ReAct 循环——LLM 看到 task 描述、
 *   上下文信息，自己决定调用哪些工具来完成这个子目标。
 *
 * 入口：Main 里用户输入 /plan <任务> 时触发
 */
public class PlanExecuteAgent {

    private static final Logger logger =
            LoggerFactory.getLogger(PlanExecuteAgent.class);

    private final Planner planner;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final LongTermMemory longTermMemory;
    private final String projectPath;
    private final SkillRegistry skillRegistry;
    private final Tracing tracing;
    private final UiEventSink events;
    private final CancellationToken cancellation;
    private final long taskTimeoutMillis;

    /** Plan 进度持久化（断点续跑）；为 null 表示不启用持久化 */
    private final PlanStore planStore;

    // 每个子 Task 执行时最多几轮工具调用
    private static final int TASK_MAX_TURNS = 10;

    // 整个计划最多执行几轮（轮次 != 步骤数：同一轮可能并行执行多个步骤）
    private static final int PLAN_MAX_ROUNDS = 10;

    // 最多重规划次数（防止 LLM 反复规划反复失败）
    private static final int MAX_REPLANS = 2;

    // 并行执行：单 task 超时和每批线程数
    private static final long DEFAULT_TASK_TIMEOUT_MILLIS =
            TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_PARALLEL_TASKS = 4;

    /** 不带持久化(子 Agent / 测试用) */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null, null, null, null);
    }

    /** 带 checkpoint 持久化(主流程用,支持断点续跑) */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, PlanStore planStore) {
        this(llmClient, toolRegistry, planStore, null, null, null);
    }

    /** 带 checkpoint + 知识共享(主流程用) */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, LongTermMemory longTermMemory, String projectPath) {
        this(llmClient, toolRegistry, planStore, longTermMemory, projectPath, null);
    }

    /** 带 checkpoint + 知识共享 + Skill(主流程用) */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, LongTermMemory longTermMemory,
                            String projectPath, SkillRegistry skillRegistry) {
        this(llmClient, toolRegistry, planStore, longTermMemory,
                projectPath, skillRegistry, Tracing.noop());
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, LongTermMemory longTermMemory,
                            String projectPath, SkillRegistry skillRegistry,
                            Tracing tracing) {
        this(llmClient, toolRegistry, planStore, longTermMemory, projectPath,
                skillRegistry, tracing, UiEventSink.noop());
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, LongTermMemory longTermMemory,
                            String projectPath, SkillRegistry skillRegistry,
                            Tracing tracing, UiEventSink events) {
        this(
                llmClient,
                toolRegistry,
                planStore,
                longTermMemory,
                projectPath,
                skillRegistry,
                tracing,
                events,
                new CancellationToken());
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, LongTermMemory longTermMemory,
                            String projectPath, SkillRegistry skillRegistry,
                            Tracing tracing, UiEventSink events,
                            CancellationToken cancellation) {
        this(
                llmClient,
                toolRegistry,
                planStore,
                longTermMemory,
                projectPath,
                skillRegistry,
                tracing,
                events,
                cancellation,
                DEFAULT_TASK_TIMEOUT_MILLIS);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                     PlanStore planStore, LongTermMemory longTermMemory,
                     String projectPath, SkillRegistry skillRegistry,
                     Tracing tracing, UiEventSink events,
                     CancellationToken cancellation,
                     long taskTimeoutMillis) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
        this.planStore = planStore;
        this.longTermMemory = longTermMemory;
        this.projectPath = projectPath;
        this.skillRegistry = skillRegistry;
        this.tracing = tracing;
        this.events = events == null ? UiEventSink.noop() : events;
        this.cancellation = cancellation == null
                ? new CancellationToken() : cancellation;
        if (taskTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "taskTimeoutMillis must be positive");
        }
        this.taskTimeoutMillis = taskTimeoutMillis;
    }

    /**
     * 执行一次 Plan-and-Execute 流程。
     *
     * 完整流程：
     *   1. 初始规划 → 2. DAG 循环执行 → 3. 某 Task 失败时触发重规划 → 4. 汇总报告
     *
     * 重规划（re-plan）机制：
     *   当 Task 执行失败时，不直接放弃整个计划。
     *   保留已完成的步骤，把失败原因 + 未完成的步骤描述发给 LLM，
     *   让它基于当前进度生成新的替代步骤。最大重规划 2 次。
     */
    public String execute(String userRequest) throws Exception {
        PlanRunStats stats = new PlanRunStats();

        // Plan 模式仍以 agent.run 作为整次用户任务的根 Span。
        try (TraceScope runScope = tracing.start("agent.run")
                .attribute("agent.mode", "PLAN")
                .attribute("agent.input_chars", userRequest.length())) {
            logPlanStarted("PLAN", userRequest.length());
            try {
                // ===== 阶段 1：初始规划 =====
                emitPlan(
                        UiEvent.PlanPhase.PLANNING,
                        0,
                        0,
                        "",
                        "正在分析并拆解任务",
                        null);

                ExecutionPlan plan;
                try {
                    plan = planner.plan(userRequest);
                } catch (Exception e) {
                    if (isCancellation(e)) {
                        throw e;
                    }
                    emitPlan(
                            UiEvent.PlanPhase.FAILED,
                            0,
                            0,
                            "",
                            "规划失败：" + safeMessage(e),
                            null);
                    runScope.fail(e);
                    finishPlanRun(
                            runScope, null, stats, "FAILED",
                            "PLANNING_FAILED", e);
                    return "规划失败：" + e.getMessage();
                }

                emitPlan(
                        UiEvent.PlanPhase.PLAN_CREATED,
                        0,
                        0,
                        "",
                        "已生成 " + plan.size() + " 个步骤",
                        plan);
                logger.atInfo()
                        .addKeyValue("event", "plan.run.planned")
                        .addKeyValue("task_count", plan.size())
                        .log("Plan 规划完成");

                String validationError = validate(plan);
                if (validationError != null) {
                    emitPlan(
                            UiEvent.PlanPhase.FAILED,
                            0,
                            0,
                            "",
                            "计划验证失败",
                            plan);
                    finishPlanRun(
                            runScope, plan, stats, "FAILED",
                            "PLAN_VALIDATION_FAILED", null);
                    return "计划验证失败：\n" + validationError;
                }

                // 初始计划先落一次盘：即使崩在第一个 task 之前，重启也能恢复出这张计划
                checkpointRequired(userRequest, 0, plan);

                String result = runPlan(userRequest, plan, 0, stats);
                String outcome = plan.isAllSuccess()
                        && stats.degradedExecutions == 0
                        ? "SUCCESS" : "DEGRADED";
                finishPlanRun(
                        runScope, plan, stats, outcome,
                        stats.stopReason, null);
                emitPlan(
                        UiEvent.PlanPhase.COMPLETED,
                        stats.rounds,
                        stats.replans,
                        "",
                        outcome,
                        plan);
                return result;
            } catch (Exception error) {
                emitPlan(
                        isCancellation(error)
                                ? UiEvent.PlanPhase.CANCELLED
                                : UiEvent.PlanPhase.FAILED,
                        stats.rounds,
                        stats.replans,
                        "",
                        isCancellation(error)
                                ? "计划已取消"
                                : safeMessage(error),
                        null);
                runScope.fail(error);
                finishPlanRun(
                        runScope, null, stats, "FAILED",
                        "UNHANDLED_EXCEPTION", error);
                throw error;
            }
        }
    }

    /**
     * 从 checkpoint 恢复后继续执行（Main 启动检测到未完成计划时调用）。
     *
     * 注意：这是"任务级恢复"——把恢复出的任务图丢回执行循环，
     * 已完成的 task 会被 getReadyTasks 自然跳过，不重放对话、不进 LLM 上下文。
     */
    public String resume(PlanStore.Checkpoint cp) throws Exception {
        PlanRunStats stats = new PlanRunStats();

        try (TraceScope runScope = tracing.start("agent.run")
                .attribute("agent.mode", "PLAN_RESUME")
                .attribute("agent.input_chars", cp.userRequest().length())) {
            logPlanStarted("PLAN_RESUME", cp.userRequest().length());
            try {
                ExecutionPlan plan = cp.plan();
                emitPlan(
                        UiEvent.PlanPhase.RESUMING,
                        0,
                        cp.replanCount(),
                        "",
                        "从断点恢复，已完成 "
                                + plan.getCompletedTasks().size() + "/"
                                + plan.size() + " 个任务",
                        plan);
                String result =
                        runPlan(cp.userRequest(), plan, cp.replanCount(), stats);
                String outcome = plan.isAllSuccess()
                        && stats.degradedExecutions == 0
                        ? "SUCCESS" : "DEGRADED";
                finishPlanRun(
                        runScope, plan, stats, outcome,
                        stats.stopReason, null);
                emitPlan(
                        UiEvent.PlanPhase.COMPLETED,
                        stats.rounds,
                        stats.replans,
                        "",
                        outcome,
                        plan);
                return result;
            } catch (Exception error) {
                emitPlan(
                        isCancellation(error)
                                ? UiEvent.PlanPhase.CANCELLED
                                : UiEvent.PlanPhase.FAILED,
                        stats.rounds,
                        stats.replans,
                        "",
                        isCancellation(error)
                                ? "计划已取消"
                                : safeMessage(error),
                        cp.plan());
                runScope.fail(error);
                finishPlanRun(
                        runScope, cp.plan(), stats, "FAILED",
                        "UNHANDLED_EXCEPTION", error);
                throw error;
            }
        }
    }

    /**
     * 核心执行循环 —— 初次执行(execute)和续跑(resume)共用。
     *
     * checkpoint 落盘时机：
     *   - task 提交前先落 IN_PROGRESS（崩溃恢复为未知状态，不自动重放）
     *   - 每个 task 到达 COMPLETED/FAILED 之后
     *   - 每次重规划改动计划结构之后
     *   - 全部确定完成后删除；含中断/超时未知状态时保留
     */
    private String runPlan(
            String userRequest,
            ExecutionPlan plan,
            int replanCount,
            PlanRunStats stats)
            throws Exception {
        if (hasInterruptedTasks(plan)) {
            /*
             * Safety belongs to the execution layer, not only the CLI.
             * Direct API callers therefore cannot bypass the non-replayable
             * checkpoint guard.
             */
            stats.replans = replanCount;
            stats.stopReason = "UNCERTAIN_TASK_STATE";
            return buildReport(plan, 0, replanCount);
        }
        // ===== 阶段 2：执行（带重规划）=====
        emitPlan(
                UiEvent.PlanPhase.EXECUTING,
                0,
                replanCount,
                "",
                "开始按依赖顺序执行",
                plan);

        int round = 0;
        stats.replans = replanCount;

        while (!plan.isAllComplete() && round < PLAN_MAX_ROUNDS) {
            ensureNotInterrupted();
            round++;
            stats.rounds = round;
            List<Task> readyTasks = plan.getReadyTasks();

            // ---- 死锁检测 ----
            if (readyTasks.isEmpty()) {
                // 如果还有重规划机会，尝试重规划来打破死锁
                if (replanCount < MAX_REPLANS && !plan.getPendingTasks().isEmpty()) {
                    replanCount++;
                    stats.replans = replanCount;
                    emitPlan(
                            UiEvent.PlanPhase.REPLANNING,
                            round,
                            replanCount,
                            "",
                            "没有就绪任务，尝试修复计划",
                            plan);
                    plan = replan(userRequest, plan);
                    checkpoint(userRequest, replanCount, plan);
                    continue;
                }
                // 重规划次数用完 → 彻底卡死。删档，否则下次启动会反复恢复到同一个死锁
                if (planStore != null) planStore.delete();
                stats.stopReason = "NO_READY_TASK";
                Map<String, String> blocked = plan.getBlockedTasks();
                StringBuilder diag = new StringBuilder();
                diag.append("执行中断：第 ").append(round)
                        .append(" 轮没有就绪任务。\n\n被阻塞的任务：\n");
                for (var entry : blocked.entrySet()) {
                    diag.append("  ").append(entry.getKey())
                            .append(" — ").append(entry.getValue()).append("\n");
                }
                return diag.toString();
            }

            emitPlan(
                    UiEvent.PlanPhase.ROUND_STARTED,
                    round,
                    replanCount,
                    "",
                    "本轮 " + readyTasks.size() + " 个就绪任务",
                    plan);

            // 标记所有就绪 task 为 IN_PROGRESS（主线程，不存在竞态）
            for (Task task : readyTasks) {
                plan.updateTask(task.getId(), Task.Status.IN_PROGRESS, "");
                emitPlan(
                        UiEvent.PlanPhase.TASK_STARTED,
                        round,
                        replanCount,
                        task.getId(),
                        task.getDescription(),
                        plan);
            }
            /*
             * Persist "started" before submitting workers. If the process
             * dies after a side effect but before result collection, load()
             * will classify the step as interrupted instead of replaying it.
             */
            checkpointRequired(userRequest, replanCount, plan);

            // 并行提交：每批创建 daemon 线程池，用完即清理
            int parallelism = Math.min(readyTasks.size(), MAX_PARALLEL_TASKS);
            ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
                Thread t = new Thread(r, "plan-worker");
                t.setDaemon(true);
                return t;
            });
            boolean anyFailedThisRound = false;
            CompletionService<TaskResult> completion =
                    new ExecutorCompletionService<>(executor);
            Map<Future<TaskResult>, RunningTask> running =
                    new LinkedHashMap<>();
            try {
                for (Task task : readyTasks) {
                    final String taskPrompt = buildTaskPrompt(task, plan);
                    CancellationToken taskCancellation =
                            cancellation.childScope();
                    var work = ContextAwareTasks.wrap(
                            () -> executeOneTask(
                                    task,
                                    taskPrompt,
                                    userRequest,
                                    taskCancellation));
                    Future<TaskResult> future =
                            completion.submit(work::get);
                    running.put(
                            future,
                            new RunningTask(
                                    task,
                                    taskCancellation,
                                    System.nanoTime()
                                            + TimeUnit.MILLISECONDS.toNanos(
                                                    taskTimeoutMillis)));
                }

                /*
                 * Collect in actual completion order. Unlike
                 * CompletableFuture.orTimeout, these are the real executor
                 * Futures, so a timeout can interrupt the underlying worker
                 * and cancel its task-scoped token.
                 */
                while (!running.isEmpty()) {
                    ensureNotInterrupted();
                    long waitStartedAt = System.nanoTime();
                    long waitNanos = running.values().stream()
                            .mapToLong(value ->
                                    Math.max(
                                            0L,
                                            value.deadlineNanos()
                                                    - waitStartedAt))
                            .min()
                            .orElse(0L);
                    Future<TaskResult> finished = completion.poll(
                            waitNanos, TimeUnit.NANOSECONDS);
                    if (finished != null) {
                        RunningTask metadata = running.remove(finished);
                        if (metadata == null) {
                            continue;
                        }
                        TaskResult result;
                        try {
                            result = finished.get();
                        } catch (CancellationException cancelled) {
                            result = failedTaskResult(
                                    metadata.task().getId(),
                                    "执行已取消",
                                    "TASK_CANCELLED");
                        } catch (ExecutionException failed) {
                            result = failedTaskResult(
                                    metadata.task().getId(),
                                    "执行异常: " + safeMessage(failed),
                                    "ASYNC_FAILURE");
                        }
                        anyFailedThisRound |= applyTaskResult(
                                userRequest,
                                replanCount,
                                plan,
                                stats,
                                round,
                                result);
                        continue;
                    }

                    long timeoutNow = System.nanoTime();
                    List<Map.Entry<Future<TaskResult>, RunningTask>>
                            expired = running.entrySet().stream()
                                    .filter(entry ->
                                            entry.getValue()
                                                    .deadlineNanos()
                                                    <= timeoutNow)
                                    .toList();
                    for (var entry : expired) {
                        RunningTask metadata = entry.getValue();
                        metadata.cancellation().cancel();
                        entry.getKey().cancel(true);
                        running.remove(entry.getKey());
                        TaskResult timeout = failedTaskResult(
                                metadata.task().getId(),
                                PlanStore.INTERRUPTED_RESULT_PREFIX
                                        + "执行超时（"
                                        + timeoutLabel() + "）"
                                        + "；部分副作用可能已经发生，"
                                        + "不会自动重试",
                                "TASK_TIMEOUT");
                        anyFailedThisRound |= applyTaskResult(
                                userRequest,
                                replanCount,
                                plan,
                                stats,
                                round,
                                timeout);
                    }
                }

            } catch (Exception error) {
                /*
                 * A cancelled batch must never be persisted with tasks stuck
                 * in IN_PROGRESS. Completed results already consumed above
                 * remain terminal; uncertain work is never replayed
                 * automatically because it may already have side effects.
                 */
                markInterruptedTasks(plan);
                checkpoint(userRequest, replanCount, plan);
                throw error;
            } finally {
                running.forEach((future, metadata) -> {
                    metadata.cancellation().cancel();
                    future.cancel(true);
                });
                executor.shutdownNow();
                if (!awaitTermination(executor, 2, TimeUnit.SECONDS)) {
                    cancellation.markUnsafeToReuse();
                    cancellation.cancel();
                }
            }
            ensureNotInterrupted();

            if (hasInterruptedTasks(plan)) {
                /*
                 * Timeout/cancellation has unknown external state. Replanning
                 * could execute the same mutation a second time, so stop at
                 * the durable checkpoint and require explicit inspection.
                 */
                stats.stopReason = "UNCERTAIN_TASK_STATE";
                break;
            }

            // ---- 重规划逻辑 ----
            // 条件：本轮有失败 + 还有未完成的 PENDING Task + 重规划次数没用完
            if (anyFailedThisRound
                    && !plan.getPendingTasks().isEmpty()
                    && replanCount < MAX_REPLANS) {

                replanCount++;
                stats.replans = replanCount;
                emitPlan(
                        UiEvent.PlanPhase.REPLANNING,
                        round,
                        replanCount,
                        "",
                        "检测到步骤失败，基于当前进度重新规划",
                        plan);
                plan = replan(userRequest, plan);
                checkpoint(userRequest, replanCount, plan);
            }
        }

        // ===== 阶段 3：汇总报告 + 清理 =====
        // 全部到终态才删档；因 PLAN_MAX_ROUNDS 退出但仍有 PENDING 时保留存档，
        // 这正是"任务太长没跑完"的合法续跑场景。
        boolean interrupted = hasInterruptedTasks(plan);
        if (plan.isAllComplete() && !interrupted && planStore != null) {
            planStore.delete();
        }
        if (interrupted) {
            stats.stopReason = "UNCERTAIN_TASK_STATE";
        } else if (!plan.isAllComplete()) {
            stats.stopReason = "MAX_ROUNDS";
        } else if (!plan.isAllSuccess()) {
            stats.stopReason = "TASK_FAILED";
        } else if (stats.degradedExecutions > 0) {
            stats.stopReason = "TASK_DEGRADED";
        }
        return buildReport(plan, round, replanCount);
    }

    /** 记录整次 Plan 的入口；正文只输出到交互终端，不进入日志。 */
    private static void logPlanStarted(String mode, int inputChars) {
        logger.atInfo()
                .addKeyValue("event", "plan.run.started")
                .addKeyValue("mode", mode)
                .addKeyValue("input_chars", inputChars)
                .log("Plan 执行开始");
    }

    /** 结束整次 Plan，并将最终状态和累计统计同时写入 Span 与日志。 */
    private static void finishPlanRun(
            TraceScope scope,
            ExecutionPlan plan,
            PlanRunStats stats,
            String outcome,
            String stopReason,
            Throwable error) {
        long taskCount = plan == null ? 0 : plan.size();
        long completedTasks = countTasks(plan, Task.Status.COMPLETED);
        long failedTasks = countTasks(plan, Task.Status.FAILED);
        long pendingTasks = plan == null ? 0
                : plan.getAllTasks().stream()
                        .filter(task -> task.getStatus() == Task.Status.PENDING
                                || task.getStatus() == Task.Status.IN_PROGRESS)
                        .count();

        if ("FAILED".equals(outcome) && error == null) {
            scope.error(stopReason, stopReason);
        }
        scope.attribute("agent.outcome", outcome)
                .attribute("plan.stop_reason", stopReason)
                .attribute("plan.task.count", taskCount)
                .attribute("plan.completed_task_count", completedTasks)
                .attribute("plan.failed_task_count", failedTasks)
                .attribute("plan.pending_task_count", pendingTasks)
                .attribute("plan.execution_round_count", stats.rounds)
                .attribute("plan.replan_count", stats.replans)
                .attribute(
                        "plan.task_execution_count",
                        stats.taskExecutions)
                .attribute(
                        "plan.degraded_execution_count",
                        stats.degradedExecutions)
                .attribute(
                        "plan.worker_llm_call_count",
                        stats.workerLlmCalls)
                .attribute(
                        "plan.worker_tool_call_count",
                        stats.workerToolCalls)
                .attribute(
                        "gen_ai.input_tokens",
                        stats.workerInputTokens)
                .attribute(
                        "gen_ai.output_tokens",
                        stats.workerOutputTokens);

        var event = switch (outcome) {
            case "SUCCESS" -> logger.atInfo();
            case "DEGRADED" -> logger.atWarn();
            default -> logger.atError();
        };
        event.addKeyValue(
                        "event",
                        "FAILED".equals(outcome)
                                ? "plan.run.failed"
                                : "plan.run.completed")
                .addKeyValue("outcome", outcome)
                .addKeyValue("stop_reason", stopReason)
                .addKeyValue("task_count", taskCount)
                .addKeyValue("completed_tasks", completedTasks)
                .addKeyValue("failed_tasks", failedTasks)
                .addKeyValue("pending_tasks", pendingTasks)
                .addKeyValue("rounds", stats.rounds)
                .addKeyValue("replans", stats.replans)
                .addKeyValue("task_executions", stats.taskExecutions)
                .addKeyValue(
                        "successful_executions",
                        stats.successfulExecutions)
                .addKeyValue(
                        "degraded_executions",
                        stats.degradedExecutions)
                .addKeyValue("failed_executions", stats.failedExecutions)
                .addKeyValue("worker_attempts", stats.workerAttempts)
                .addKeyValue("review_attempts", stats.reviewAttempts)
                .addKeyValue("worker_turns", stats.workerTurns)
                .addKeyValue("worker_llm_calls", stats.workerLlmCalls)
                .addKeyValue("worker_tool_calls", stats.workerToolCalls)
                .addKeyValue("recovered_errors", stats.recoveredErrors)
                .addKeyValue(
                        "worker_input_tokens",
                        stats.workerInputTokens)
                .addKeyValue(
                        "worker_output_tokens",
                        stats.workerOutputTokens)
                .addKeyValue("duration_ms", scope.elapsedMillis());
        if (error != null) {
            event.addKeyValue(
                            "error_type",
                            error.getClass().getSimpleName())
                    .setCause(error);
        }
        event.log("FAILED".equals(outcome)
                ? "Plan 执行失败" : "Plan 执行完成");
    }

    private static long countTasks(
            ExecutionPlan plan,
            Task.Status status) {
        return plan == null ? 0
                : plan.getAllTasks().stream()
                        .filter(task -> task.getStatus() == status)
                        .count();
    }

    /** Best-effort terminal checkpoint; returns whether persistence succeeded. */
    private boolean checkpoint(
            String userRequest,
            int replanCount,
            ExecutionPlan plan) {
        return planStore == null
                || planStore.save(new PlanStore.Checkpoint(
                        userRequest, replanCount, plan));
    }

    /**
     * A mutation may start only after its IN_PROGRESS state is durable.
     * Otherwise a stale PENDING checkpoint could replay the same side effect
     * after a crash.
     */
    private void checkpointRequired(
            String userRequest,
            int replanCount,
            ExecutionPlan plan) throws java.io.IOException {
        if (!checkpoint(userRequest, replanCount, plan)) {
            throw new java.io.IOException(
                    "无法持久化 Plan 检查点；为避免重复执行，未启动任何 Worker");
        }
    }

    /**
     * 重规划：保留已完成步骤，让 LLM 基于当前状态生成新的替代步骤。
     *
     * 做法：
     *   1. 收集当前状态（完成、失败、待完成）
     *   2. 拼一段 prompt 发给 LLM："前面 X 失败了，请规划新的后续步骤"
     *   3. LLM 返回新的 Task 列表（id 用 task_r0, task_r1... 避免冲突）
     *   4. 移除所有 PENDING Task，保留 COMPLETED + FAILED
     *   5. 加入新 Task，用新的序号
     *
     * 为什么不移除 FAILED Task？
     *   已失败的步骤保留在报告中，让用户知道历史发生了什么。
     */
    private ExecutionPlan replan(String userRequest, ExecutionPlan oldPlan) throws Exception {
        String context = oldPlan.buildContextForReplan();
        int nextIdx = oldPlan.nextReplanIndex();

        // 拼重规划 prompt
        String replanPrompt = """
                你是一个任务规划专家。前面已经执行了部分步骤，但有步骤失败了。
                请基于当前已完成的进度，重新规划后续步骤来完成原始任务。

                规则：
                1. 已完成的步骤不要重做（它们的结果还在）
                2. 针对失败和未完成的步骤，设计新的替代方案
                3. 依赖关系可以引用已完成的步骤 id
                4. 新步骤的 id 请从 task_r%d 开始递增
                5. 输出纯 JSON 数组，格式同前

                原始任务：%s

                %s

                请输出新的后续步骤 JSON：""".formatted(nextIdx, userRequest, context);

        // 调 LLM 做重规划
        var messages = List.of(
                new LlmClient.Message("system", Planner.PLANNER_SYSTEM_PROMPT),
                new LlmClient.Message("user", replanPrompt)
        );
        var reply = llmClient.chatRaw(messages, null);
        String json = Planner.extractJson(reply.content);

        var taskList = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                json,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

        // 移除所有 PENDING Task，保留 COMPLETED + FAILED
        oldPlan.removePendingTasks();

        // 加入新 Task
        for (Map<String, Object> item : taskList) {
            String id = (String) item.get("id");
            String desc = (String) item.get("description");
            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) item.getOrDefault("dependencies", List.of());

            Task newTask = new Task(id, desc, deps);
            oldPlan.addTask(newTask);
        }

        emitPlan(
                UiEvent.PlanPhase.PLAN_CREATED,
                0,
                0,
                "",
                "重规划完成",
                oldPlan);

        String err = validate(oldPlan);
        if (err != null) {
            throw new RuntimeException("重规划验证失败：" + err);
        }

        return oldPlan;
    }

    /**
     * 为子 Task 构造提示词。
     *
     * 包含两部分：
     *   1. 任务描述（"你要完成什么"）
     *   2. 上下文（已完成 Task 的结果，让 LLM 知道前面干了什么）
     *
     * 为什么要把已完成 Task 的结果传进去？
     *   比如 task_2 要在 task_1 创建的 pom.xml 基础上改依赖——
     *   如果不知道 task_1 干了什么，它就得重新读文件，浪费 token。
     */
    private String buildTaskPrompt(Task task, ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("请完成以下子任务：\n\n");
        sb.append(task.getDescription()).append("\n");

        // 附上已完成 Task 的结果，作为上下文
        List<Task> completed = plan.getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.Status.COMPLETED)
                .toList();

        if (!completed.isEmpty()) {
            sb.append("\n---\n");
            sb.append("前面已完成的步骤及其结果（供参考，可以直接基于这些结果操作）：\n\n");
            for (Task t : completed) {
                sb.append("【").append(t.getId()).append("】")
                        .append(t.getDescription()).append("\n");
                if (t.getResult() != null && !t.getResult().isBlank()) {
                    // 截断过长结果
                    String r = t.getResult();
                    if (r.length() > 2000) {
                        r = r.substring(0, 2000) + "...[已截断]";
                    }
                    sb.append("结果：").append(r).append("\n");
                }
                sb.append("\n");
            }
        }

        // 帮 LLM 理解当前项目状态
        sb.append("---\n");
        sb.append("提示：必要时使用 list_dir 查看目录结构，使用 read_file 读取已有文件，" +
                "使用 write_file 创建或修改文件。");

        return sb.toString();
    }

    /** 汇总所有 Task 的执行结果 */
    private String buildReport(ExecutionPlan plan, int rounds, int replans) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 执行报告 =====\n");
        sb.append("总步骤: ").append(plan.size())
                .append("，执行轮次: ").append(rounds);
        if (replans > 0) {
            sb.append("，重规划: ").append(replans).append(" 次");
        }
        sb.append("\n");

        long success = plan.getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.Status.COMPLETED).count();
        long failed = plan.getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.Status.FAILED).count();

        sb.append("成功: ").append(success)
                .append("，失败: ").append(failed).append("\n\n");

        for (Task t : plan.getAllTasks()) {
            String icon = switch (t.getStatus()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case IN_PROGRESS -> "⏳";
                case PENDING -> "⬜";
            };
            sb.append(icon).append(" ").append(t.getId())
                    .append(" — ").append(t.getDescription()).append("\n");
            if (t.getStatus() == Task.Status.FAILED) {
                sb.append("   失败原因: ")
                        .append(t.getResult()).append("\n");
            }
        }

        if (hasInterruptedTasks(plan)) {
            sb.append("\n⛔ 至少一个步骤在执行中断或超时，"
                    + "其外部副作用状态未知。已保留检查点，"
                    + "请检查工作区后再决定是否创建新计划。");
        } else if (plan.isAllSuccess()) {
            sb.append("\n🎉 所有步骤执行成功！");
        } else if (plan.isAllComplete()) {
            sb.append("\n⚠️ 执行完成，但部分步骤失败。");
        } else {
            sb.append("\n⚠️ 执行未完全完成（达到最大轮次限制）。");
        }

        return sb.toString();
    }

    /**
     * 计划完整性校验。在真正执行前拒绝明显有问题的计划。
     *
     * 检查项：
     *   1. 循环依赖（三色 DFS 检测）
     *   2. 依赖了不存在的 task id
     *   3. 空计划
     *
     * @return 非 null = 有问题（返回错误描述），null = 校验通过
     */
    private String validate(ExecutionPlan plan) {
        // 空计划
        if (plan.size() == 0) {
            return "计划为空，LLM 未能生成有效步骤。";
        }

        // 循环依赖
        if (plan.hasCycle()) {
            return "检测到循环依赖。LLM 生成的步骤中存在依赖环路，" +
                    "请重新组织任务描述或手动指定顺序。";
        }

        // 依赖了不存在的 task id
        for (Task t : plan.getAllTasks()) {
            for (String depId : t.getDependencies()) {
                if (plan.getTask(depId) == null) {
                    return String.format(
                            "Task %s 依赖了不存在的 %s（该 id 未在计划中定义）。",
                            t.getId(), depId);
                }
            }
        }

        return null;  // 校验通过
    }

    // ────── 并行执行支持 ──────

    /**
     * 整次 Plan 运行的内存计数器。
     *
     * <p>这里只累加 Worker 已经返回的统计，不调用 LLM，也不保存正文。</p>
     */
    private static final class PlanRunStats {
        private int rounds;
        private int replans;
        private int taskExecutions;
        private int successfulExecutions;
        private int degradedExecutions;
        private int failedExecutions;
        private int workerAttempts;
        private int reviewAttempts;
        private int workerTurns;
        private int workerLlmCalls;
        private int workerToolCalls;
        private int recoveredErrors;
        private long workerInputTokens;
        private long workerOutputTokens;
        private String stopReason = "COMPLETED";

        private void add(TaskResult result) {
            taskExecutions++;
            switch (result.outcome) {
                case "SUCCESS" -> successfulExecutions++;
                case "DEGRADED" -> degradedExecutions++;
                default -> failedExecutions++;
            }
            workerAttempts += result.workerAttempts;
            reviewAttempts += result.reviewAttempts;
            workerTurns += result.workerTurns;
            workerLlmCalls += result.workerLlmCalls;
            workerToolCalls += result.workerToolCalls;
            recoveredErrors += result.recoveredErrors;
            workerInputTokens += result.workerInputTokens;
            workerOutputTokens += result.workerOutputTokens;
        }
    }

    /** 单个 Task 内可能执行多次 Worker，用这个对象累加各次运行结果。 */
    private static final class TaskRunStats {
        private int workerAttempts;
        private int reviewAttempts;
        private int workerTurns;
        private int workerLlmCalls;
        private int workerToolCalls;
        private int recoveredErrors;
        private long workerInputTokens;
        private long workerOutputTokens;
        private boolean workerDegraded;
        private boolean mutatingToolUsed;

        private void add(Agent.RunResult result) {
            workerAttempts++;
            workerTurns += result.turns();
            workerLlmCalls += result.llmCalls();
            workerToolCalls += result.toolCalls();
            recoveredErrors += result.recoveredErrors();
            workerInputTokens += result.inputTokens();
            workerOutputTokens += result.outputTokens();
            workerDegraded |= !"SUCCESS".equals(result.outcome());
            mutatingToolUsed |= result.mutatingToolUsed();
        }
    }

    /** 单个 Task 的执行结果和确定性统计，供主线程更新计划并生成汇总。 */
    private record TaskResult(
            String taskId,
            Task.Status status,
            String result,
            String icon,
            String summary,
            String outcome,
            String errorType,
            int workerAttempts,
            int reviewAttempts,
            int workerTurns,
            int workerLlmCalls,
            int workerToolCalls,
            int recoveredErrors,
            long workerInputTokens,
            long workerOutputTokens) {
    }

    private record RunningTask(
            Task task,
            CancellationToken cancellation,
            long deadlineNanos) {
    }

    /**
     * 执行一个子任务（Worker + Review + 重做），供并行线程调用。
     * 不碰 plan.updateTask（交给主线程收集结果后统一做），不写 checkpoint。
     */
    private TaskResult executeOneTask(
            Task task,
            String taskPrompt,
            String userRequest,
            CancellationToken taskCancellation) {
        TaskRunStats stats = new TaskRunStats();

        // 一个 plan.task 覆盖 Worker 执行、Reviewer 审查及可能的重做。
        try (MdcScope ignored = MdcScope.put("task_id", task.getId());
             TraceScope taskScope = tracing.start("plan.task")
                .attribute("plan.task.id", task.getId())
                .attribute("plan.task.description_chars",
                        task.getDescription().length())
                .attribute("plan.task.dependency_count",
                        task.getDependencies().size())) {
            logger.atInfo()
                    .addKeyValue("event", "plan.task.started")
                    .addKeyValue(
                            "dependency_count",
                            task.getDependencies().size())
                    .log("Plan 子任务开始");
            try {
                taskCancellation.throwIfCancellationRequested();
                /*
                 * 创建子 Agent，并把同一个 SkillRegistry 继续向下传递。
                 * 因此普通 ReAct 模式和 /plan 拆出的 Worker 使用同一套联网决策规则，
                 * 不会出现主 Agent 会联网、子 Agent 却看不到 web-access 的情况。
                 */
                Agent subAgent = createSubAgent(
                        task, userRequest, taskCancellation);
                Agent.RunResult workerResult =
                        subAgent.runDetailed(taskPrompt);
                taskCancellation.throwIfCancellationRequested();
                stats.add(workerResult);
                String result = workerResult.content();

                // 自动沉淀
                if (longTermMemory != null && projectPath != null) {
                    taskCancellation.throwIfCancellationRequested();
                    LessonExtractor.tryExtract(
                            subAgent.getHistory(),
                            llmClient,
                            longTermMemory,
                            projectPath);
                }

                // Review + 打回重做(最多 2 次)
                boolean taskPassed = false;
                String reviewFeedback = "";
                Reviewer reviewer =
                        new Reviewer(
                                llmClient, tracing, taskCancellation);
                for (int retry = 0; retry < 3; retry++) {
                    taskCancellation.throwIfCancellationRequested();
                    if (retry > 0) {
                        String retryPrompt = taskPrompt
                                + "\n\n【审查反馈】上次结果被驳回: "
                                + reviewFeedback
                                + "\n请修正后重新执行。";
                        subAgent = createSubAgent(
                                task, userRequest, taskCancellation);
                        workerResult = subAgent.runDetailed(retryPrompt);
                        taskCancellation.throwIfCancellationRequested();
                        stats.add(workerResult);
                        result = workerResult.content();
                    }

                    stats.reviewAttempts++;
                    ReviewResult review = reviewer.review(
                            userRequest, task.getDescription(), result);
                    if (review.approved()) {
                        taskPassed = true;
                        break;
                    }
                    reviewFeedback = String.join("; ", review.issues());
                    if (stats.mutatingToolUsed) {
                        /*
                         * A fresh Agent would not know which external changes
                         * the rejected attempt already made. Automatic redo
                         * could repeat non-idempotent commands or writes.
                         */
                        recordTaskMetrics(taskScope, "FAILED", stats);
                        logTaskCompleted(taskScope, "FAILED", stats);
                        return taskResult(
                                task,
                                Task.Status.FAILED,
                                PlanStore.INTERRUPTED_RESULT_PREFIX
                                        + "审查未通过，但本次 Worker 已执行"
                                        + "变更型工具；为避免重复副作用，"
                                        + "未自动重做。审查意见："
                                        + reviewFeedback,
                                "❌",
                                "审查未通过；变更已发生，需人工检查",
                                "FAILED",
                                "REVIEW_REJECTED_AFTER_MUTATION",
                                stats);
                    }
                }

                String finalResult = taskPassed
                        ? result
                        : result + "\n\n[审查未通过] " + reviewFeedback;
                String outcome = taskPassed && !stats.workerDegraded
                        ? "SUCCESS" : "DEGRADED";
                recordTaskMetrics(taskScope, outcome, stats);
                logTaskCompleted(taskScope, outcome, stats);
                return taskResult(
                        task,
                        Task.Status.COMPLETED,
                        finalResult,
                        "SUCCESS".equals(outcome) ? "✅" : "⚠️",
                        taskPassed && !stats.workerDegraded
                                ? "完成"
                                : "降级完成,保留结果继续",
                        outcome,
                        null,
                        stats);
            } catch (Exception e) {
                taskScope.fail(e);
                recordTaskMetrics(taskScope, "FAILED", stats);
                boolean uncertain =
                        e instanceof Agent.PartialExecutionException
                                || stats.workerToolCalls > 0;
                String result = uncertain
                        ? PlanStore.INTERRUPTED_RESULT_PREFIX
                                + "子任务在工具执行后中断，"
                                + "外部副作用状态未知；不会自动重试。"
                        : "执行异常: " + safeMessage(e);
                String summary = uncertain
                        ? "状态未知：请先检查工作区"
                        : "失败: " + safeMessage(e);
                String errorType = uncertain
                        ? "PARTIAL_TOOL_EFFECTS"
                        : e.getClass().getSimpleName();
                logger.atError()
                        .addKeyValue("event", "plan.task.failed")
                        .addKeyValue("outcome", "FAILED")
                        .addKeyValue(
                                "error_type",
                                errorType)
                        .addKeyValue(
                                "worker_attempts", stats.workerAttempts)
                        .addKeyValue(
                                "review_attempts", stats.reviewAttempts)
                        .addKeyValue(
                                "duration_ms", taskScope.elapsedMillis())
                        .setCause(e)
                        .log("Plan 子任务失败");
                return taskResult(
                        task,
                        Task.Status.FAILED,
                        result,
                        "❌",
                        summary,
                        "FAILED",
                        errorType,
                        stats);
            }
        }
    }

    /** 为首次执行和审查打回后的重做创建隔离的 Worker Agent。 */
    private Agent createSubAgent(
            Task task,
            String userRequest,
            CancellationToken taskCancellation) {
        MemoryManager subMemory;
        if (longTermMemory != null && projectPath != null) {
            subMemory = new MemoryManager(
                    longTermMemory,
                    projectPath);
            subMemory.setGoal("【总任务】" + userRequest
                    + "\n【当前步骤】" + task.getDescription());
        } else {
            subMemory = new MemoryManager();
        }
        return new Agent(
                llmClient,
                toolRegistry,
                subMemory,
                skillRegistry,
                task.getId(),
                tracing,
                events,
                taskCancellation);
    }

    private void emitPlan(
            UiEvent.PlanPhase phase,
            int round,
            int replanCount,
            String taskId,
            String message,
            ExecutionPlan plan) {
        List<UiEvent.PlanTaskView> tasks = plan == null
                ? List.of()
                : plan.getAllTasks().stream()
                        .map(UiEvent.PlanTaskView::from)
                        .toList();
        events.emit(new UiEvent.PlanChanged(
                phase,
                round,
                replanCount,
                taskId,
                message,
                tasks));
    }

    private void ensureNotInterrupted() throws InterruptedException {
        cancellation.throwIfCancellationRequested();
    }

    private String timeoutLabel() {
        if (taskTimeoutMillis % 1000L == 0L) {
            return (taskTimeoutMillis / 1000L) + " 秒";
        }
        return taskTimeoutMillis + " 毫秒";
    }

    private static boolean hasInterruptedTasks(ExecutionPlan plan) {
        return plan != null
                && plan.getAllTasks().stream()
                        .anyMatch(task ->
                                task.getStatus() == Task.Status.FAILED
                                && task.getResult() != null
                                && task.getResult().startsWith(
                                        PlanStore.INTERRUPTED_RESULT_PREFIX));
    }

    private static void markInterruptedTasks(ExecutionPlan plan) {
        for (Task task : plan.getAllTasks()) {
            if (task.getStatus() == Task.Status.IN_PROGRESS) {
                plan.updateTask(
                        task.getId(),
                        Task.Status.FAILED,
                        PlanStore.INTERRUPTED_RESULT_PREFIX
                                + "步骤在运行中被取消；可能已产生部分副作用，"
                                + "不会自动重试。");
            }
        }
    }

    private boolean applyTaskResult(
            String userRequest,
            int replanCount,
            ExecutionPlan plan,
            PlanRunStats stats,
            int round,
            TaskResult result) {
        plan.updateTask(
                result.taskId, result.status, result.result);
        /*
         * Persist every terminal transition immediately. A sibling may still
         * be running when Ctrl+C arrives; batch-only persistence would replay
         * already completed side effects after restart.
         */
        checkpoint(userRequest, replanCount, plan);
        stats.add(result);
        boolean failed = result.status == Task.Status.FAILED;
        if (failed) {
            try (MdcScope ignored =
                         MdcScope.put("task_id", result.taskId)) {
                logger.atError()
                        .addKeyValue("event", "plan.task.failed")
                        .addKeyValue("outcome", "FAILED")
                        .addKeyValue(
                                "error_type", result.errorType)
                        .log("Plan 子任务超时或异步执行失败");
            }
        }
        emitPlan(
                UiEvent.PlanPhase.TASK_COMPLETED,
                round,
                replanCount,
                result.taskId,
                result.summary,
                plan);
        return failed;
    }

    private static boolean awaitTermination(
            ExecutorService executor,
            long timeout,
            TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    if (executor.awaitTermination(
                            remaining, TimeUnit.NANOSECONDS)) {
                        return true;
                    }
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isCancellation(Throwable error) {
        return error instanceof InterruptedException
                || error instanceof java.io.InterruptedIOException
                || Thread.currentThread().isInterrupted();
    }

    /** 把 Task 统计同时写入 Span，便于以后接 Jaeger 时查看。 */
    private static void recordTaskMetrics(
            TraceScope scope,
            String outcome,
            TaskRunStats stats) {
        scope.attribute("plan.task.outcome", outcome)
                .attribute("plan.task.worker_attempt_count", stats.workerAttempts)
                .attribute("plan.task.review_attempt_count", stats.reviewAttempts)
                .attribute("plan.task.worker_turn_count", stats.workerTurns)
                .attribute("plan.task.worker_llm_call_count", stats.workerLlmCalls)
                .attribute("plan.task.worker_tool_call_count", stats.workerToolCalls)
                .attribute(
                        "plan.task.recovered_error_count",
                        stats.recoveredErrors)
                .attribute(
                        "gen_ai.input_tokens",
                        stats.workerInputTokens)
                .attribute(
                        "gen_ai.output_tokens",
                        stats.workerOutputTokens);
    }

    /** 每个 Task 只生成一条边界汇总，不读取或记录正文。 */
    private static void logTaskCompleted(
            TraceScope scope,
            String outcome,
            TaskRunStats stats) {
        var event = "SUCCESS".equals(outcome)
                ? logger.atInfo() : logger.atWarn();
        event.addKeyValue("event", "plan.task.completed")
                .addKeyValue("outcome", outcome)
                .addKeyValue("worker_attempts", stats.workerAttempts)
                .addKeyValue("review_attempts", stats.reviewAttempts)
                .addKeyValue("worker_turns", stats.workerTurns)
                .addKeyValue("worker_llm_calls", stats.workerLlmCalls)
                .addKeyValue("worker_tool_calls", stats.workerToolCalls)
                .addKeyValue("recovered_errors", stats.recoveredErrors)
                .addKeyValue("worker_input_tokens", stats.workerInputTokens)
                .addKeyValue("worker_output_tokens", stats.workerOutputTokens)
                .addKeyValue("duration_ms", scope.elapsedMillis())
                .log("Plan 子任务执行完成");
    }

    private static TaskResult taskResult(
            Task task,
            Task.Status status,
            String result,
            String icon,
            String summary,
            String outcome,
            String errorType,
            TaskRunStats stats) {
        return new TaskResult(
                task.getId(),
                status,
                result,
                icon,
                summary,
                outcome,
                errorType,
                stats.workerAttempts,
                stats.reviewAttempts,
                stats.workerTurns,
                stats.workerLlmCalls,
                stats.workerToolCalls,
                stats.recoveredErrors,
                stats.workerInputTokens,
                stats.workerOutputTokens);
    }

    private static TaskResult failedTaskResult(
            String taskId,
            String message,
            String errorType) {
        return new TaskResult(
                taskId,
                Task.Status.FAILED,
                message,
                "❌",
                "超时/异常",
                "FAILED",
                errorType,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
