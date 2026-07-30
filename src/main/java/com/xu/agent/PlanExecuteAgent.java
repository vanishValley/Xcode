package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.memory.KnowledgeBase;
import com.xu.memory.LessonExtractor;
import com.xu.memory.MemoryManager;
import com.xu.memory.PlanStore;
import com.xu.observability.ContextAwareTasks;
import com.xu.observability.MdcScope;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.plan.ExecutionPlan;
import com.xu.plan.Planner;
import com.xu.plan.Task;
import com.xu.skill.SkillRegistry;
import com.xu.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    private final KnowledgeBase knowledgeBase;
    private final String projectPath;
    private final SkillRegistry skillRegistry;
    private final Tracing tracing;

    /** Plan 进度持久化（断点续跑）；为 null 表示不启用持久化 */
    private final PlanStore planStore;

    // 每个子 Task 执行时最多几轮工具调用
    private static final int TASK_MAX_TURNS = 10;

    // 整个计划最多执行几轮（轮次 != 步骤数：同一轮可能并行执行多个步骤）
    private static final int PLAN_MAX_ROUNDS = 10;

    // 最多重规划次数（防止 LLM 反复规划反复失败）
    private static final int MAX_REPLANS = 2;

    // 并行执行：单 task 超时和每批线程数
    private static final int TASK_TIMEOUT_SECONDS = 300;
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
                            PlanStore planStore, KnowledgeBase knowledgeBase, String projectPath) {
        this(llmClient, toolRegistry, planStore, knowledgeBase, projectPath, null);
    }

    /** 带 checkpoint + 知识共享 + Skill(主流程用) */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, KnowledgeBase knowledgeBase,
                            String projectPath, SkillRegistry skillRegistry) {
        this(llmClient, toolRegistry, planStore, knowledgeBase,
                projectPath, skillRegistry, Tracing.noop());
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanStore planStore, KnowledgeBase knowledgeBase,
                            String projectPath, SkillRegistry skillRegistry,
                            Tracing tracing) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
        this.planStore = planStore;
        this.knowledgeBase = knowledgeBase;
        this.projectPath = projectPath;
        this.skillRegistry = skillRegistry;
        this.tracing = tracing;
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
                System.out.println("\n[规划阶段] 正在分析任务...");

                ExecutionPlan plan;
                try {
                    plan = planner.plan(userRequest);
                } catch (Exception e) {
                    runScope.fail(e);
                    finishPlanRun(
                            runScope, null, stats, "FAILED",
                            "PLANNING_FAILED", e);
                    return "规划失败：" + e.getMessage();
                }

                System.out.println(plan.summary());
                logger.atInfo()
                        .addKeyValue("event", "plan.run.planned")
                        .addKeyValue("task_count", plan.size())
                        .log("Plan 规划完成");

                String validationError = validate(plan);
                if (validationError != null) {
                    finishPlanRun(
                            runScope, plan, stats, "FAILED",
                            "PLAN_VALIDATION_FAILED", null);
                    return "计划验证失败：\n" + validationError;
                }

                // 初始计划先落一次盘：即使崩在第一个 task 之前，重启也能恢复出这张计划
                checkpoint(userRequest, 0, plan);

                String result = runPlan(userRequest, plan, 0, stats);
                String outcome = plan.isAllSuccess()
                        && stats.degradedExecutions == 0
                        ? "SUCCESS" : "DEGRADED";
                finishPlanRun(
                        runScope, plan, stats, outcome,
                        stats.stopReason, null);
                return result;
            } catch (Exception error) {
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
                System.out.println("\n[续跑] 从断点恢复，已完成 "
                        + plan.getCompletedTasks().size() + "/"
                        + plan.size() + " 个任务");
                String result =
                        runPlan(cp.userRequest(), plan, cp.replanCount(), stats);
                String outcome = plan.isAllSuccess()
                        && stats.degradedExecutions == 0
                        ? "SUCCESS" : "DEGRADED";
                finishPlanRun(
                        runScope, plan, stats, outcome,
                        stats.stopReason, null);
                return result;
            } catch (Exception error) {
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
     * checkpoint 落盘时机（只在"终态"落盘，绕开 IN_PROGRESS 卡死问题）：
     *   - 每个 task 到达 COMPLETED/FAILED 之后
     *   - 每次重规划改动计划结构之后
     *   - 全部完成后删除存档
     */
    private String runPlan(
            String userRequest,
            ExecutionPlan plan,
            int replanCount,
            PlanRunStats stats)
            throws Exception {
        // ===== 阶段 2：执行（带重规划）=====
        System.out.println("[执行阶段] 开始按依赖顺序执行...");

        int round = 0;
        stats.replans = replanCount;

        while (!plan.isAllComplete() && round < PLAN_MAX_ROUNDS) {
            round++;
            stats.rounds = round;
            List<Task> readyTasks = plan.getReadyTasks();

            // ---- 死锁检测 ----
            if (readyTasks.isEmpty()) {
                // 如果还有重规划机会，尝试重规划来打破死锁
                if (replanCount < MAX_REPLANS && !plan.getPendingTasks().isEmpty()) {
                    replanCount++;
                    stats.replans = replanCount;
                    System.out.println("\n[重规划 #" + replanCount + "] 没有就绪任务，尝试重规划...");
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

            System.out.println("\n--- 第 " + round + " 轮，就绪任务: "
                    + readyTasks.size() + " 个 ---");

            // 标记所有就绪 task 为 IN_PROGRESS（主线程，不存在竞态）
            for (Task task : readyTasks) {
                plan.updateTask(task.getId(), Task.Status.IN_PROGRESS, "");
                System.out.println("  执行: " + task.getId() + " — " + task.getDescription());
            }

            // 并行提交：每批创建 daemon 线程池，用完即清理
            int parallelism = Math.min(readyTasks.size(), MAX_PARALLEL_TASKS);
            ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
                Thread t = new Thread(r, "plan-worker");
                t.setDaemon(true);
                return t;
            });
            boolean anyFailedThisRound = false;
            try {
                List<CompletableFuture<TaskResult>> futures = new ArrayList<>();
                for (Task task : readyTasks) {
                    final String taskPrompt = buildTaskPrompt(task, plan);
                    // CompletableFuture 不会自动传播 ThreadLocal/MDC；
                    // wrap() 在提交线程捕获父 Context，让 plan.task 仍属于本次 agent.run。
                    CompletableFuture<TaskResult> cf = CompletableFuture
                            .supplyAsync(ContextAwareTasks.wrap(
                                    () -> executeOneTask(
                                            task, taskPrompt, userRequest)),
                                    executor)
                            .orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .exceptionally(ex -> failedTaskResult(
                                    task.getId(),
                                    "执行超时或异常: " + safeMessage(ex),
                                    "TIMEOUT_OR_ASYNC_FAILURE"));
                    futures.add(cf);
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 收集结果：主线程统一更新 plan，避免并发写
                for (CompletableFuture<TaskResult> cf : futures) {
                    TaskResult r = cf.getNow(null);
                    plan.updateTask(r.taskId, r.status, r.result);
                    stats.add(r);
                    if (r.status == Task.Status.FAILED) {
                        anyFailedThisRound = true;
                        if ("TIMEOUT_OR_ASYNC_FAILURE".equals(r.errorType)) {
                            try (MdcScope ignored =
                                         MdcScope.put("task_id", r.taskId)) {
                                logger.atError()
                                        .addKeyValue(
                                                "event",
                                                "plan.task.failed")
                                        .addKeyValue(
                                                "outcome", "FAILED")
                                        .addKeyValue(
                                                "error_type", r.errorType)
                                        .log("Plan 子任务超时或异步执行失败");
                            }
                        }
                    }
                    System.out.println("  " + r.icon + " " + r.taskId + " — " + r.summary);
                }

                // 本轮全部完成后统一落盘
                checkpoint(userRequest, replanCount, plan);
            } finally {
                executor.shutdownNow();
            }

            // ---- 重规划逻辑 ----
            // 条件：本轮有失败 + 还有未完成的 PENDING Task + 重规划次数没用完
            if (anyFailedThisRound
                    && !plan.getPendingTasks().isEmpty()
                    && replanCount < MAX_REPLANS) {

                replanCount++;
                stats.replans = replanCount;
                System.out.println("\n[重规划 #" + replanCount
                        + "] 检测到步骤失败，基于已完成的进度重新规划...");
                plan = replan(userRequest, plan);
                checkpoint(userRequest, replanCount, plan);
            }
        }

        // ===== 阶段 3：汇总报告 + 清理 =====
        // 全部到终态才删档；因 PLAN_MAX_ROUNDS 退出但仍有 PENDING 时保留存档，
        // 这正是"任务太长没跑完"的合法续跑场景。
        if (plan.isAllComplete() && planStore != null) {
            planStore.delete();
        }
        if (!plan.isAllComplete()) {
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

    /** 落 checkpoint。planStore 为 null（未启用持久化）时无操作；写失败由 PlanStore 内部兜底。 */
    private void checkpoint(String userRequest, int replanCount, ExecutionPlan plan) {
        if (planStore != null) {
            planStore.save(new PlanStore.Checkpoint(userRequest, replanCount, plan));
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

        System.out.println("重规划后：\n" + oldPlan.summary());

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

        if (plan.isAllSuccess()) {
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

        private void add(Agent.RunResult result) {
            workerAttempts++;
            workerTurns += result.turns();
            workerLlmCalls += result.llmCalls();
            workerToolCalls += result.toolCalls();
            recoveredErrors += result.recoveredErrors();
            workerInputTokens += result.inputTokens();
            workerOutputTokens += result.outputTokens();
            workerDegraded |= !"SUCCESS".equals(result.outcome());
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

    /**
     * 执行一个子任务（Worker + Review + 重做），供并行线程调用。
     * 不碰 plan.updateTask（交给主线程收集结果后统一做），不写 checkpoint。
     */
    private TaskResult executeOneTask(Task task, String taskPrompt, String userRequest) {
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
                /*
                 * 创建子 Agent，并把同一个 SkillRegistry 继续向下传递。
                 * 因此普通 ReAct 模式和 /plan 拆出的 Worker 使用同一套联网决策规则，
                 * 不会出现主 Agent 会联网、子 Agent 却看不到 web-access 的情况。
                 */
                Agent subAgent = createSubAgent(task, userRequest);
                Agent.RunResult workerResult =
                        subAgent.runDetailed(taskPrompt);
                stats.add(workerResult);
                String result = workerResult.content();

                // 自动沉淀
                if (knowledgeBase != null && projectPath != null) {
                    LessonExtractor.tryExtract(
                            subAgent.getHistory(),
                            llmClient,
                            knowledgeBase,
                            projectPath);
                }

                // Review + 打回重做(最多 2 次)
                boolean taskPassed = false;
                String reviewFeedback = "";
                Reviewer reviewer = new Reviewer(llmClient, tracing);
                for (int retry = 0; retry < 3; retry++) {
                    if (retry > 0) {
                        String retryPrompt = taskPrompt
                                + "\n\n【审查反馈】上次结果被驳回: "
                                + reviewFeedback
                                + "\n请修正后重新执行。";
                        subAgent = createSubAgent(task, userRequest);
                        workerResult = subAgent.runDetailed(retryPrompt);
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
                logger.atError()
                        .addKeyValue("event", "plan.task.failed")
                        .addKeyValue("outcome", "FAILED")
                        .addKeyValue(
                                "error_type",
                                e.getClass().getSimpleName())
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
                        "执行异常: " + safeMessage(e),
                        "❌",
                        "失败: " + safeMessage(e),
                        "FAILED",
                        e.getClass().getSimpleName(),
                        stats);
            }
        }
    }

    /** 为首次执行和审查打回后的重做创建隔离的 Worker Agent。 */
    private Agent createSubAgent(Task task, String userRequest) {
        MemoryManager subMemory;
        if (knowledgeBase != null && projectPath != null) {
            subMemory = new MemoryManager(knowledgeBase, projectPath);
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
                tracing);
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
