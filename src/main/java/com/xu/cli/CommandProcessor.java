package com.xu.cli;

import com.xu.agent.Agent;
import com.xu.agent.PlanExecuteAgent;
import com.xu.hitl.HitlToolRegistry;
import com.xu.memory.LongTermMemory;
import com.xu.memory.MemoryManager;
import com.xu.memory.MemoryRecord;
import com.xu.memory.MemoryScope;
import com.xu.plan.PlanStore;
import com.xu.skill.SkillRegistry;
import com.xu.skill.SkillStateStore;
import com.xu.ui.SafeDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/** 与界面无关的命令分发器，由 JLine TUI 和 plain 模式共同使用。 */
public final class CommandProcessor {

    private static final Logger logger =
            LoggerFactory.getLogger(CommandProcessor.class);

    public enum Kind {
        NONE, INFO, SUCCESS, WARNING, ERROR, ASSISTANT
    }

    public record Result(
            boolean exit,
            Kind kind,
            String text,
            boolean renderedByEvents) {
        public Result {
            kind = kind == null ? Kind.NONE : kind;
            text = SafeDisplay.redact(text == null ? "" : text);
        }

        public static Result of(Kind kind, String text) {
            return new Result(false, kind, text, false);
        }

        public static Result eventRendered(String text) {
            return new Result(false, Kind.ASSISTANT, text, true);
        }
    }

    private final Agent agent;
    private final PlanExecuteAgent planAgent;
    private final HitlToolRegistry registry;
    private final MemoryManager memory;
    private final PlanStore planStore;
    private final SkillRegistry skills;
    private final SkillStateStore skillStates;
    private final String model;
    private final Path projectRoot;
    private final Path logFile;

    public CommandProcessor(
            Agent agent,
            PlanExecuteAgent planAgent,
            HitlToolRegistry registry,
            MemoryManager memory,
            PlanStore planStore,
            SkillRegistry skills,
            SkillStateStore skillStates,
            String model,
            Path projectRoot,
            Path logFile) {
        this.agent = agent;
        this.planAgent = planAgent;
        this.registry = registry;
        this.memory = memory;
        this.planStore = planStore;
        this.skills = skills;
        this.skillStates = skillStates;
        this.model = model;
        this.projectRoot = projectRoot;
        this.logFile = logFile;
    }

    public Result execute(String rawInput) {
        String input = rawInput == null ? "" : rawInput;
        String command = input.strip();
        if (command.isEmpty()) {
            return Result.of(Kind.NONE, "");
        }
        try {
            if ("quit".equalsIgnoreCase(command)
                    || "exit".equalsIgnoreCase(command)
                    || "/exit".equalsIgnoreCase(command)) {
                return new Result(true, Kind.INFO, "再见！", false);
            }
            if ("/clear".equalsIgnoreCase(command)) {
                agent.clear();
                memory.clearTask();
                registry.clearApprovalState();
                return new Result(
                        false,
                        Kind.SUCCESS,
                        "已清空对话历史、任务上下文和本次会话审批状态。",
                        true);
            }
            if ("/hitl on".equalsIgnoreCase(command)) {
                registry.setEnabled(true);
                return Result.of(
                        Kind.SUCCESS,
                        "HITL 已启用：危险工具执行前会请求确认。");
            }
            if ("/hitl off".equalsIgnoreCase(command)) {
                registry.setEnabled(false);
                registry.clearApprovalState();
                return Result.of(
                        Kind.WARNING,
                        "HITL 已关闭：工具将直接执行。");
            }
            if ("/hitl".equalsIgnoreCase(command)) {
                return Result.of(
                        Kind.INFO,
                        "HITL " + (registry.isEnabled() ? "已启用" : "已关闭")
                                + "\n受保护：write_file、execute_command、"
                                + "create_project、revert_turn、mcp__*");
            }
            if ("/help".equalsIgnoreCase(command)) {
                return Result.of(Kind.INFO, helpText());
            }
            if ("/status".equalsIgnoreCase(command)) {
                return Result.of(Kind.INFO, statusText());
            }
            if ("/tools".equalsIgnoreCase(command)) {
                StringBuilder text = new StringBuilder(
                        "已注册工具（" + registry.names().size() + "）\n");
                for (String name : registry.names().stream().sorted().toList()) {
                    var tool = registry.get(name);
                    text.append("  ").append(name).append(" — ")
                            .append(tool.description()).append('\n');
                }
                return Result.of(Kind.INFO, text.toString().stripTrailing());
            }
            if ("/skills".equalsIgnoreCase(command)
                    || "/skill list".equalsIgnoreCase(command)) {
                StringBuilder text = new StringBuilder(
                        "Skills（" + skills.allSkills().size() + "）\n");
                for (var skill : skills.allSkills()) {
                    String state = skillStates.isDisabled(skill.name())
                            ? "off" : "on";
                    text.append("  [").append(state).append("] ")
                            .append(skill.name()).append(" — ")
                            .append(skill.description()).append(" (")
                            .append(skill.source()).append(")\n");
                }
                return Result.of(Kind.INFO, text.toString().stripTrailing());
            }
            if ("/skill reload".equalsIgnoreCase(command)) {
                skills.reload();
                StringBuilder text = new StringBuilder(
                        "已重新加载 " + skills.allSkills().size() + " 个 Skill");
                for (String warning : skills.warnings()) {
                    text.append("\n  ! ").append(warning);
                }
                return Result.of(Kind.SUCCESS, text.toString());
            }
            if (command.startsWith("/skill on ")
                    || command.startsWith("/skill off ")) {
                boolean enable = command.startsWith("/skill on ");
                String name = command.substring(enable ? 10 : 11).strip();
                if (skills.findSkill(name).isEmpty()) {
                    return Result.of(Kind.ERROR, "Skill 不存在：" + name);
                }
                if (enable) {
                    skillStates.enable(name);
                } else {
                    skillStates.disable(name);
                }
                SkillStateStore.DISABLED_HOLDER =
                        skillStates.disabledNames();
                return Result.of(
                        Kind.SUCCESS,
                        "Skill " + name + " 已" + (enable ? "启用" : "禁用"));
            }
            if ("/save".equalsIgnoreCase(command)) {
                return Result.of(
                        Kind.INFO,
                        "用法：/save [-g] <事实内容>\n"
                                + "  -g 表示跨项目的全局记忆");
            }
            if (command.startsWith("/save ")) {
                return saveMemory(command.substring(6).strip());
            }
            if ("/memory".equalsIgnoreCase(command)) {
                var records = memory.listFacts();
                if (records.isEmpty()) {
                    return Result.of(Kind.INFO, "暂无长期记忆。");
                }
                StringBuilder text = new StringBuilder(
                        "长期记忆（" + records.size() + "）\n");
                for (MemoryRecord record : records) {
                    text.append("  ").append(record.id()).append(' ');
                    if (record.scope() == MemoryScope.GLOBAL) {
                        text.append("[全局] ");
                    }
                    text.append(record.content()).append('\n');
                }
                return Result.of(Kind.INFO, text.toString().stripTrailing());
            }
            if ("/memory clear".equalsIgnoreCase(command)) {
                memory.clearFacts();
                return Result.of(Kind.SUCCESS, "长期记忆已清空。");
            }
            if ("/history clear".equalsIgnoreCase(command)) {
                return Result.of(
                        Kind.INFO,
                        "plain 模式不记录输入历史；"
                                + "已有 TUI 历史请在 TUI 中运行 /history clear 清理。");
            }
            if (command.startsWith("/plan")) {
                String task = command.substring(5).strip();
                if (task.isEmpty()) {
                    return Result.of(
                            Kind.INFO,
                            "用法：/plan <任务描述>\n"
                                    + "例如：/plan 创建一个 Spring Boot demo");
                }
                String report = planAgent.execute(task);
                agent.injectContext(
                        "【Plan 模式执行结果】刚刚为以下任务运行了 /plan：\n"
                                + task
                                + "\n实际状态以执行报告为准；"
                                + "若报告含中断、超时或副作用未知，"
                                + "不得假定任务已完成，也不要自动重试。\n\n"
                                + "执行报告：\n" + report);
                return Result.of(Kind.ASSISTANT, report);
            }
            if (command.startsWith("/")) {
                return Result.of(
                        Kind.WARNING,
                        "未知命令：" + command.split("\\s+", 2)[0]
                                + "\n输入 /help 查看可用命令。");
            }

            return Result.eventRendered(agent.run(input));
        } catch (Exception error) {
            boolean interrupted = error instanceof InterruptedException
                    || error instanceof java.io.InterruptedIOException
                    || (error instanceof Agent.PartialExecutionException
                    partial && partial.cancelled())
                    || Thread.currentThread().isInterrupted();
            if (interrupted) {
                Thread.interrupted();
                return new Result(
                        false,
                        Kind.WARNING,
                        "当前任务已取消。",
                        true);
            }
            logger.atError()
                    .addKeyValue("event", "command.execute.failed")
                    .addKeyValue(
                            "error_type",
                            error.getClass().getSimpleName())
                    .setCause(error)
                    .log("命令执行失败");
            return Result.of(
                    Kind.ERROR,
                    "执行失败：" + SafeDisplay.errorPreview(
                            safeMessage(error))
                            + "\n详细信息已写入 " + logFile);
        }
    }

    public Optional<PlanStore.Checkpoint> unfinishedPlan() {
        if (!planStore.exists()) {
            return Optional.empty();
        }
        PlanStore.Checkpoint checkpoint = planStore.load();
        if (checkpoint == null
                || (checkpoint.plan().isAllComplete()
                && !planStore.hasInterruptedTasks(checkpoint))) {
            planStore.delete();
            return Optional.empty();
        }
        return Optional.of(checkpoint);
    }

    public Result resume(PlanStore.Checkpoint checkpoint) {
        if (planStore.hasInterruptedTasks(checkpoint)) {
            return Result.of(
                    Kind.WARNING,
                    "该计划包含执行中断的步骤，可能已经产生部分副作用，"
                            + "为避免重复写文件或重复执行命令，系统不会自动续跑。\n"
                            + "请先检查工作区，再丢弃存档并创建新的 /plan。");
        }
        try {
            String report = planAgent.resume(checkpoint);
            agent.injectContext(
                    "【Plan 续跑结果】刚刚尝试恢复以下计划：\n"
                            + checkpoint.userRequest()
                            + "\n实际状态以执行报告为准；"
                            + "不得把中断、超时或副作用未知的步骤视为已完成。\n\n"
                            + "执行报告：\n" + report);
            return Result.of(Kind.ASSISTANT, report);
        } catch (Exception error) {
            boolean interrupted = error instanceof InterruptedException
                    || error instanceof java.io.InterruptedIOException
                    || Thread.currentThread().isInterrupted();
            if (interrupted) {
                Thread.interrupted();
                return Result.of(Kind.WARNING, "计划续跑已取消。");
            }
            return Result.of(
                    Kind.ERROR,
                    "续跑失败：" + SafeDisplay.errorPreview(
                            safeMessage(error)));
        }
    }

    public Result discardUnfinishedPlan() {
        planStore.delete();
        return Result.of(Kind.SUCCESS, "已丢弃未完成的计划。");
    }

    public String unfinishedPlanSummary(PlanStore.Checkpoint checkpoint) {
        var plan = checkpoint.plan();
        StringBuilder text = new StringBuilder();
        text.append("发现未完成计划\n")
                .append("  任务：").append(checkpoint.userRequest()).append('\n')
                .append("  进度：").append(plan.getCompletedTasks().size())
                .append('/').append(plan.size()).append('\n');
        if (planStore.hasInterruptedTasks(checkpoint)) {
            text.append("  ! 存在执行中断的步骤；为避免重复副作用，"
                    + "不会自动续跑。\n");
        }
        for (var task : plan.getAllTasks()) {
            String marker = switch (task.getStatus()) {
                case COMPLETED -> "✓";
                case FAILED -> "✗";
                case IN_PROGRESS -> "●";
                case PENDING -> "○";
            };
            text.append("  ").append(marker).append(' ')
                    .append(task.getId()).append("  ")
                    .append(task.getDescription()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    public String model() {
        return model;
    }

    public boolean hitlEnabled() {
        return registry.isEnabled();
    }

    private Result saveMemory(String body) {
        MemoryScope scope = MemoryScope.PROJECT;
        if (body.startsWith("-g ")) {
            scope = MemoryScope.GLOBAL;
            body = body.substring(3).strip();
        }
        if (body.isEmpty()) {
            return Result.of(Kind.INFO, "用法：/save [-g] <事实内容>");
        }
        LongTermMemory.SaveResult decision =
                memory.saveFact(body, scope);
        String scopeName = scope == MemoryScope.GLOBAL ? "全局" : "项目";
        return switch (decision) {
            case COMMIT -> Result.of(
                    Kind.SUCCESS, "已保存为" + scopeName + "记忆：" + body);
            case REJECT -> Result.of(Kind.INFO, "已存在相同记忆，未重复保存。");
            case MERGE -> Result.of(Kind.INFO, "已有相似记忆，未重复保存。");
            case DEFER -> Result.of(Kind.WARNING, "记忆已挂起，等待人工确认。");
        };
    }

    private String statusText() {
        return "模型    " + model
                + "\n项目    " + projectRoot
                + "\nHITL    " + (registry.isEnabled() ? "on" : "off")
                + "\n工具    " + registry.names().size()
                + "\nSkills  " + skills.allSkills().size()
                + "\n日志    " + logFile;
    }

    public static String helpText() {
        return """
                命令
                  /help                 显示帮助
                  /status               查看当前运行状态
                  /tools                查看已注册工具
                  /skills               查看 Skills
                  /skill reload         重新扫描 Skills
                  /skill on|off <name>  启用或禁用 Skill
                  /plan <任务>          规划并并行执行复杂任务
                  /save [-g] <事实>     保存长期记忆
                  /memory               查看长期记忆
                  /memory clear         清空长期记忆
                  /history clear        清空 TUI 输入历史
                  /hitl on|off          切换危险操作审批
                  /clear                清空对话与本次审批状态
                  exit                  退出

                快捷键
                  ↑/↓  历史    Ctrl+R  搜索历史    Tab  补全    Ctrl+J  换行
                  Ctrl+L  清屏    Ctrl+C  取消    Ctrl+D  主提示符退出""";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
