package com.xu.team;

import com.xu.agent.Agent;
import com.xu.llm.LlmClient;
import com.xu.memory.LongTermMemory;
import com.xu.memory.MemoryManager;
import com.xu.observability.MdcScope;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.skill.SkillRegistry;
import com.xu.tool.ToolRegistry;
import com.xu.ui.SafeDisplay;
import com.xu.ui.UiEvent;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** 一次 /team 的装配与收尾。主 Agent 自己决定分工，Coordinator 不生成固定 DAG。 */
public final class TeamCoordinator {
    private final LlmClient client;
    private final ToolRegistry tools;
    private final LongTermMemory sharedMemory;
    private final SkillRegistry skills;
    private final Path projectRoot, dataDirectory;
    private final Tracing tracing;
    private final UiEventSink events;
    private final CancellationToken rootCancellation;
    private final TeamConfig config;

    public TeamCoordinator(LlmClient client, ToolRegistry tools, LongTermMemory sharedMemory,
                           SkillRegistry skills, Path projectRoot, Path dataDirectory,
                           Tracing tracing, UiEventSink events, CancellationToken cancellation) {
        this(client, tools, sharedMemory, skills, projectRoot, dataDirectory, tracing, events, cancellation, TeamConfig.defaults());
    }
    public TeamCoordinator(LlmClient client, ToolRegistry tools, LongTermMemory sharedMemory,
                           SkillRegistry skills, Path projectRoot, Path dataDirectory,
                           Tracing tracing, UiEventSink events, CancellationToken cancellation, TeamConfig config) {
        this.client = client; this.tools = tools; this.sharedMemory = sharedMemory; this.skills = skills;
        this.projectRoot = projectRoot; this.dataDirectory = dataDirectory;
        this.tracing = tracing; this.events = events; this.rootCancellation = cancellation; this.config = config;
    }

    public String execute(String task) throws Exception {
        TeamTypes.text(task, "task", 16384);
        rootCancellation.throwIfCancellationRequested();
        String projectRules = projectInstructions();
        CancellationToken teamToken = rootCancellation.childScope();
        teamToken.beginRun();
        String teamId = UUID.randomUUID().toString();
        TeamEventStore store = new TeamEventStore(dataDirectory.resolve("teams").resolve(teamId));
        TeamWorkspaceService workflow;
        try { workflow = new TeamWorkspaceService(projectRoot, store.directory().resolve("workspace-flow")); }
        catch (Exception e) { store.close(); throw e; }
        TeamBudget budget = new TeamBudget(config);
        TeamRuntime runtime = new TeamRuntime(config, teamToken, (rt, id, work, token) -> {
            boolean writer = work.profile() == TeamTypes.Profile.ISOLATED_WRITE;
            Path memberRoot = projectRoot;
            ToolRegistry memberTools = tools;
            if (writer) {
                var workspace = workflow.prepare(id, work.dependsOn());
                memberRoot = Path.of(workspace.root());
                memberTools = tools.forWorkspace(memberRoot, workflow.environment(id), token);
            }
            TeamModelClient model = new TeamModelClient(client, budget, config, rt, id, token);
            MemoryManager memory = MemoryManager.forTeam(sharedMemory, model, memberRoot.toString());
            memory.setGoal("总任务: " + task + "\n当前委派: " + work.task());
            TeamToolRegistry view = new TeamToolRegistry(memberTools, rt, id, false, memberRoot, token, workflow, writer);
            Agent agent = new Agent(model, view, memory, skills, id, tracing, events, token);
            String environment = "\n任务工作目录: " + memberRoot + (writer ? "\n" + TeamJson.write(workflow.workspace(id)) : "");
            return (input, hooks) -> {
                if (writer) workflow.invalidate(id);
                try { return agent.runCoordinated(input + projectRules + environment, view.bind(hooks)); }
                finally { view.clearRun(); }
            };
        }, store, event -> {
            if (java.util.Set.of("CREATED", "RUNNING", "QUESTION", "RESULT", "FAILURE", "STOP_REQUESTED").contains(event.type())) {
                events.emit(new UiEvent.Notice(UiEvent.Severity.INFO,
                        "[Team " + teamId.substring(0, 8) + "][" + event.agentId() + "] "
                                + event.type() + ": " + SafeDisplay.redact(event.content())));
            }
        });
        Thread leadThread = Thread.currentThread();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "team-deadline"); t.setDaemon(true); return t;
        });
        String content = "";
        String outcome = "PARTIAL";
        boolean interrupted = false;
        try (MdcScope ignored = MdcScope.put("team_id", teamId);
             TraceScope scope = tracing.start("coding.task").attribute("task.mode", "TEAM").attribute("team.id", teamId)) {
            store.manifest(Map.of("teamId", teamId, "goal", task, "status", "ACTIVE",
                    "startedAt", java.time.Instant.now().toString(), "recoverySupported", false));
            watchdog.scheduleAtFixedRate(() -> {
                if (rootCancellation.isCancelled() || !rootCancellation.isReusable() || budget.expired() || !store.failure().isBlank()) {
                    runtime.cancelAll(); leadThread.interrupt();
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
            try {
                TeamModelClient model = new TeamModelClient(client, budget, config, runtime, "lead", teamToken);
                MemoryManager memory = MemoryManager.forTeam(sharedMemory, model, projectRoot.toString());
                memory.setGoal(task);
                TeamToolRegistry view = new TeamToolRegistry(tools, runtime, "lead", true, projectRoot, teamToken, workflow, false);
                Agent lead = new Agent(model, view, memory, skills, "team-" + teamId.substring(0, 8), tracing, events, teamToken);
                Agent.RunResult result;
                try { result = lead.runCoordinated(task + projectRules, view.bind(runtime.hooks("lead", true))); }
                finally { view.clearRun(); }
                content = result.content();
                outcome = "SUCCESS".equals(result.outcome()) && runtime.acceptedFinish() ? "SUCCESS" : "PARTIAL";
                if (workflow.hasDeferred() && outcome.equals("SUCCESS")) outcome = "PARTIAL";
                if (runtime.snapshots().stream().anyMatch(s -> s.state() == TeamTypes.State.FAILED)
                        && outcome.equals("SUCCESS")) outcome = "PARTIAL";
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                boolean budgetFailure = cause instanceof TeamException error && error.code().equals("BUDGET_EXCEEDED");
                outcome = rootCancellation.isCancelled() ? "CANCELLED" : budget.expired() || budgetFailure ? "BUDGET_EXCEEDED" : "FAILED";
                content = "团队执行未完成：" + SafeDisplay.redact(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            } finally {
                watchdog.shutdownNow();
                interrupted = Thread.interrupted();
                try {
                    if (!watchdog.awaitTermination(1, TimeUnit.SECONDS)) rootCancellation.markUnsafeToReuse();
                } catch (InterruptedException e) { interrupted = true; }
                runtime.close();
                interrupted |= Thread.interrupted();
            }
            if (rootCancellation.isCancelled()) outcome = "CANCELLED";
            if (runtime.unsafe()) outcome = "INTERRUPTED_UNSAFE";
            if (!store.failure().isBlank() && outcome.equals("SUCCESS")) outcome = "PARTIAL";
            store.manifest(Map.of("teamId", teamId, "goal", task, "status", outcome,
                    "finishedAt", java.time.Instant.now().toString(), "budget", budget.snapshot(),
                    "members", runtime.snapshots(), "contracts", runtime.contracts(), "recoverySupported", false));
            scope.attribute("task.outcome", outcome);
            tracing.metrics().recordTask("TEAM", outcome, scope.elapsedMillis());
        } finally {
            watchdog.shutdownNow();
            store.close();
            workflow.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!store.failure().isBlank() && outcome.equals("SUCCESS")) outcome = "PARTIAL";
        StringBuilder report = new StringBuilder("Team 执行状态：").append(outcome).append("\n\n").append(content);
        if (!"SUCCESS".equals(outcome)) {
            report.append("\n\n已保留的成员结果：");
            for (TeamTypes.Result result : runtime.results()) {
                String partial = result.content() == null ? "" : result.content();
                report.append("\n- ").append(result.agentId()).append(" / ").append(result.outcome())
                        .append(": ").append(partial.substring(0, Math.min(500, partial.length())));
            }
            report.append("\n继续前请检查工作区与已有结果，不要盲目重放可能已执行的操作。");
        }
        report.append("\n\n团队统计：").append(TeamJson.write(budget.snapshot()));
        report.append("\n执行记录：").append(store.directory());
        report.append("\n工作区与集成交付记录：").append(workflow.directory().resolve("workflow.json"));
        report.append("\n集成成果保存在独立候选分支；没有自动合入用户分支或推送远端。");
        if (!runtime.failureReason().isBlank()) report.append("\n运行时终止原因：").append(runtime.failureReason());
        if (!store.failure().isBlank()) report.append("\n记录不完整：").append(store.failure());
        return SafeDisplay.redact(report.toString());
    }

    private String projectInstructions() throws java.io.IOException {
        Path rules = projectRoot.resolve("AGENTS.md");
        if (!Files.isRegularFile(rules) || Files.size(rules) > 32 * 1024) return "";
        if (!rules.toRealPath().startsWith(projectRoot.toRealPath())) return "";
        return "\n\n【项目 AGENTS.md；不能变更运行时权限】\n" + Files.readString(rules);
    }
}
