package com.xu.cli;

import com.xu.agent.Agent;
import com.xu.agent.PlanExecuteAgent;
import com.xu.llm.LlmClient;
import com.xu.memory.GovernanceGate;
import com.xu.memory.KnowledgeBase;
import com.xu.memory.MemoryManager;
import com.xu.memory.MemoryRecord;
import com.xu.memory.MemoryScope;
import com.xu.memory.PlanStore;
import com.xu.memory.SessionStore;
import com.xu.config.XcodePaths;
import com.xu.plan.ExecutionPlan;
import com.xu.plan.Task;
import com.xu.hitl.HitlToolRegistry;
import com.xu.hitl.TerminalHitlHandler;
import com.xu.mcp.ChromeMcpClient;
import com.xu.observability.Tracing;
import com.xu.tool.ToolRegistry;
import com.xu.tool.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {
        // 必须先确定项目日志目录，再触发 Tracing/SLF4J 初始化。
        XcodePaths paths =
                new XcodePaths(Path.of(System.getProperty("user.dir")));
        Map<String, String> env = loadEnv();
        configureLogging(paths, env);

        // 整个进程只创建一个 Tracing，保证所有组件使用同一套 SDK 配置。
        // try-with-resources 会在程序退出前刷新并关闭 Span 导出器。
        try (Tracing tracing = Tracing.create()) {
            run(args, tracing, paths, env);
        }
    }

    private static void run(
            String[] args,
            Tracing tracing,
            XcodePaths paths,
            Map<String, String> env)
            throws Exception {
        // 1. 加载配置
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY",
                env.get("DEEPSEEK_API_KEY"));
        String model = System.getenv().getOrDefault("DEEPSEEK_MODEL",
                env.getOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("请设置 DEEPSEEK_API_KEY");
            return;
        }

        // 2. 将同一个 Tracing 沿构造器传入整条业务调用链
        LlmClient client = new LlmClient(apiKey, model, tracing);

        // 3. 注册工具
        HitlToolRegistry registry = new HitlToolRegistry(
                new TerminalHitlHandler());
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ListDirTool());
        registry.register(new ExecuteCommandTool());
        registry.register(new GlobFilesTool());

        // 4. 项目路径 + 创建 Memory 系统 + Agent
        paths.initProjectIfNeeded();
        String projectPath = paths.projectRoot().toString();
        Path projectDataDir = paths.projectDataDir();

        SessionStore sessionStore = new SessionStore(paths);
        KnowledgeBase knowledgeBase = KnowledgeBase.create(projectDataDir);
        MemoryManager memoryManager = new MemoryManager(
                sessionStore, knowledgeBase, client, projectPath);
        PlanStore planStore = new PlanStore(projectDataDir);

        /*
         * 5. 装配 Skill 系统。
         *
         * 这里没有单独实现“联网意图分类器”。Agent 的 system prompt 只看到
         * Skill 的名称和简介；当用户问题命中 web-access 的适用范围时，模型在
         * 当前 ReAct 轮调用 load_skill，读取完整规则后再选择联网工具。
         *
         * 三层覆盖顺序为 builtin → user → project，同名 Skill 越靠近项目优先级越高。
         */
        com.xu.skill.SkillRegistry skillRegistry = new com.xu.skill.SkillRegistry(paths);
        skillRegistry.reload();
        com.xu.skill.SkillStateStore skillStateStore = new com.xu.skill.SkillStateStore(
                paths.userDir().resolve("skills.json"));
        com.xu.skill.SkillStateStore.DISABLED_HOLDER = skillStateStore.disabledNames();
        registry.registerLoadSkillTool(skillRegistry);

        /*
         * 6. 直接装配联网工具，不再增加 WebAccessModule 之类的中间层。
         *
         * web_search 依赖 WSA_API_KEY；web_fetch 只使用 HTTP，不需要 Key。
         * Chrome MCP 是增强能力，启动失败不会影响搜索、抓取和 Agent 主流程。
         */
        String searchApiKey = configValue(env, "WSA_API_KEY", "");
        if (searchApiKey.isBlank()) {
            System.out.println("[Web] 未配置 WSA_API_KEY，跳过 web_search");
        } else {
            registry.register(new WebSearchTool(searchApiKey));
        }
        registry.register(new WebFetchTool());

        ChromeMcpClient chromeMcp = null;
        if (configFlag(env, "CHROME_MCP_ENABLED", true)) {
            try {
                chromeMcp = ChromeMcpClient.start(
                        registry, env, Path.of(projectPath), tracing);
                System.out.println("[MCP] Chrome DevTools 已连接，注册 "
                        + chromeMcp.registeredToolCount() + " 个工具");
            } catch (IOException e) {
                System.out.println("[MCP] Chrome DevTools 不可用："
                        + e.getMessage());
                logger().atWarn()
                        .addKeyValue("event", "mcp.chrome.start_failed")
                        .setCause(e)
                        .log("Chrome MCP 启动失败");
            }
        }
        if (chromeMcp != null) {
            ChromeMcpClient clientToClose = chromeMcp;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(clientToClose::close, "chrome-mcp-shutdown"));
        }

        Agent agent = new Agent(
                client, registry, memoryManager, skillRegistry, tracing);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(
                client, registry, planStore, knowledgeBase,
                projectPath, skillRegistry, tracing);

        printBanner(
                model,
                registry,
                Path.of(System.getProperty("xcode.log.dir")));

        // 7. 进入交互循环
        Scanner scanner = new Scanner(System.in);

        // 启动时检测上次未完成的 Plan，询问续跑 / 丢弃 / 暂缓
        checkUnfinishedPlan(scanner, planStore, planAgent, agent);

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().strip();
            if (input.isEmpty()) continue;

            // ---- 内置命令（不经过 Agent，直接处理） ----
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                System.out.println("再见！");
                break;
            }
            if ("/clear".equalsIgnoreCase(input)) {
                agent.clear();
                memoryManager.clearTask();    // 清历史 + 清目标,新任务重新 setGoal
                registry.clearApprovalState(); // 清空 HITL 工具全部放行
                continue;
            }
            if ("/hitl on".equalsIgnoreCase(input)) {
                registry.setEnabled(true);
                System.out.println("🔒 HITL 审批已启用: write_file / execute_command / create_project / mcp__ 工具执行前将请求确认\n");
                continue;
            }
            if ("/hitl off".equalsIgnoreCase(input)) {
                registry.setEnabled(false);
                registry.clearApprovalState();
                System.out.println("🔓 HITL 审批已关闭: 所有工具直接执行\n");
                continue;
            }
            if ("/hitl".equalsIgnoreCase(input)) {
                System.out.println("HITL: " + (registry.isEnabled() ? "🔒 已启用" : "🔓 已关闭")
                        + "  |  危险工具: write_file / execute_command / create_project / revert_turn / mcp__*");
                continue;
            }
            if ("/help".equalsIgnoreCase(input)) {
                printHelp();
                continue;
            }
            if ("/tools".equalsIgnoreCase(input)) {
                printTools(registry);
                continue;
            }
            if ("/skills".equalsIgnoreCase(input)
                    || "/skill list".equalsIgnoreCase(input)) {
                System.out.println("\n已发现的 Skills（"
                        + skillRegistry.allSkills().size() + " 个）:");
                for (var skill : skillRegistry.allSkills()) {
                    String state = skillStateStore.isDisabled(skill.name())
                            ? "off" : "on";
                    System.out.println("  [" + state + "] " + skill.name()
                            + " — " + skill.description()
                            + " (" + skill.source() + ")");
                }
                continue;
            }
            if ("/skill reload".equalsIgnoreCase(input)) {
                skillRegistry.reload();
                System.out.println("[Skill] 已重新加载 "
                        + skillRegistry.allSkills().size() + " 个 Skill");
                for (String warning : skillRegistry.warnings()) {
                    System.out.println("[Skill] " + warning);
                }
                continue;
            }
            if (input.startsWith("/skill on ") || input.startsWith("/skill off ")) {
                boolean enable = input.startsWith("/skill on ");
                String name = input.substring(enable ? 10 : 11).strip();
                if (skillRegistry.findSkill(name).isEmpty()) {
                    System.out.println("[Skill] 不存在: " + name);
                    continue;
                }
                if (enable) skillStateStore.enable(name);
                else skillStateStore.disable(name);
                com.xu.skill.SkillStateStore.DISABLED_HOLDER =
                        skillStateStore.disabledNames();
                System.out.println("[Skill] " + name + " 已"
                        + (enable ? "启用" : "禁用"));
                continue;
            }
            if ("/save".equalsIgnoreCase(input)) {
                System.out.println("用法：/save [-g] <事实内容>   (-g = 全局, 跨项目通用)");
                System.out.println("例如：/save 用户偏好使用 Java 17");
                System.out.println("例如：/save -g 改完代码必须先跑测试再说完成");
                continue;
            }
            if (input.startsWith("/save ")) {
                String body = input.substring(6).strip();
                MemoryScope scope = MemoryScope.PROJECT;
                if (body.startsWith("-g ")) {            // 全局作用域(修复旧版 global 从 CLI 不可达)
                    scope = MemoryScope.GLOBAL;
                    body = body.substring(3).strip();
                }
                if (body.isEmpty()) {
                    System.out.println("用法：/save [-g] <事实内容>");
                    continue;
                }
                GovernanceGate.Decision d = memoryManager.saveFact(body, scope);
                String tag = scope == MemoryScope.GLOBAL ? "[全局] " : "";
                switch (d) {
                    case COMMIT -> System.out.println("[已保存] " + tag + body);
                    case REJECT -> System.out.println("[跳过] 已存在相同记忆");
                    case MERGE  -> System.out.println("[跳过] 已有相似记忆");
                    case DEFER  -> System.out.println("[挂起] 待人工确认");
                }
                continue;
            }
            if ("/memory".equalsIgnoreCase(input)) {
                var list = memoryManager.listFacts();
                if (list.isEmpty()) {
                    System.out.println("[记忆] 暂无长期记忆");
                } else {
                    System.out.println("[记忆] 共 " + list.size() + " 条：");
                    for (MemoryRecord m : list) {
                        String tag = m.scope() == MemoryScope.GLOBAL ? "[全局]" : "";
                        System.out.println("  " + m.id() + " " + tag + " " + m.content());
                    }
                }
                continue;
            }
            if ("/memory clear".equalsIgnoreCase(input)) {
                memoryManager.clearFacts();
                System.out.println("[记忆] 已清空");
                continue;
            }
            if (input.startsWith("/plan")) {
                String task = input.substring(5).strip();  // 去掉 "/plan" 前缀
                if (task.isEmpty()) {
                    System.out.println("用法：/plan <任务描述>");
                    System.out.println("例如：/plan 创建一个 Spring Boot demo 项目");
                    continue;
                }
                try {
                    String report = planAgent.execute(task);
                    System.out.println(report);
                    // 把 Plan 执行结果注入主 Agent，让 ReAct 模式知道刚才发生了什么
                    agent.injectContext("【Plan 模式执行结果】刚刚通过 /plan 完成了以下任务：\n"
                            + task + "\n\n执行报告：\n" + report);
                } catch (Exception e) {
                    System.err.println("Plan 执行出错: " + e.getMessage());
                    logger().atError()
                            .addKeyValue("event", "plan.execute.failed")
                            .setCause(e)
                            .log("Plan 执行失败");
                }
                continue;
            }

            // ---- 交给 Agent 处理 ----
            try {
                String result = agent.run(input);
                System.out.println("\n" + result);
            } catch (Exception e) {
                System.err.println("出错: " + e.getMessage());
                logger().atError()
                        .addKeyValue("event", "agent.execute.failed")
                        .setCause(e)
                        .log("Agent 执行失败");
            }
        }
        scanner.close();
    }

    // ---------- 断点续跑 ----------

    /** 启动时检测未完成的 Plan checkpoint，询问用户续跑 / 丢弃 / 暂缓 */
    private static void checkUnfinishedPlan(Scanner scanner, PlanStore planStore,
                                            PlanExecuteAgent planAgent, Agent agent) {
        if (!planStore.exists()) return;

        PlanStore.Checkpoint cp = planStore.load();
        // 损坏 / 已完成的残留 → 清掉，不打扰用户
        if (cp == null || cp.plan().isAllComplete()) {
            planStore.delete();
            return;
        }

        ExecutionPlan plan = cp.plan();
        System.out.println("\n[发现上次未完成的计划]");
        System.out.println("  原始任务：" + cp.userRequest());
        System.out.println("  进度：已完成 " + plan.getCompletedTasks().size()
                + "/" + plan.size());
        for (Task t : plan.getAllTasks()) {
            String icon = switch (t.getStatus()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case IN_PROGRESS -> "⏳";
                case PENDING -> "⬜";
            };
            System.out.println("    " + icon + " " + t.getId() + " — " + t.getDescription());
        }
        System.out.println("  [r] 从断点续跑    [d] 丢弃并删除    [其他] 暂不处理（保留存档）");
        System.out.print("> ");

        String choice = scanner.nextLine().strip().toLowerCase();
        if (choice.equals("r")) {
            try {
                String report = planAgent.resume(cp);
                System.out.println(report);
                // 和正常 /plan 结尾一致：把续跑结果告知主 ReAct Agent
                agent.injectContext("【Plan 续跑结果】恢复并继续了上次未完成的计划：\n"
                        + cp.userRequest() + "\n\n执行报告：\n" + report);
            } catch (Exception e) {
                System.err.println("续跑出错: " + e.getMessage());
                logger().atError()
                        .addKeyValue("event", "plan.resume.failed")
                        .setCause(e)
                        .log("Plan 续跑失败");
            }
        } else if (choice.equals("d")) {
            planStore.delete();
            System.out.println("[已丢弃未完成的计划]");
        } else {
            System.out.println("[暂不处理，存档保留，下次启动会再次询问]");
        }
    }

    // ---------- 启动信息 ----------

    /**
     * 在 Logback 首次初始化前设置当前项目的日志目录。
     * XCODE_LOG_DIR 和 XCODE_LOG_LEVEL 都支持系统环境变量或项目 .env，
     * 系统环境变量优先。
     */
    private static void configureLogging(
            XcodePaths paths,
            Map<String, String> fileEnv) {
        String configured = configValue(fileEnv, "XCODE_LOG_DIR", "");
        Path logDir = configured == null || configured.isBlank()
                ? paths.logsDir()
                : Path.of(configured).toAbsolutePath().normalize();
        System.setProperty("xcode.log.dir", logDir.toString());

        String configuredLevel = configValue(
                fileEnv, "XCODE_LOG_LEVEL", "INFO")
                .toUpperCase(Locale.ROOT);
        String logLevel = switch (configuredLevel) {
            case "TRACE", "DEBUG", "INFO", "WARN", "ERROR" ->
                    configuredLevel;
            default -> "INFO";
        };
        System.setProperty("xcode.log.level", logLevel);
    }

    /** 延迟获取 Logger，避免 Main 类加载时提前初始化 Logback。 */
    private static Logger logger() {
        return LoggerFactory.getLogger(Main.class);
    }

    private static void printBanner(
            String model, ToolRegistry registry, Path logsDir) {
        System.out.println("===== Xcode Agent ===== v1.0");
        System.out.println("模型: " + model);
        System.out.println("工具: " + registry.names());
        System.out.println("日志: " + logsDir.resolve("xcode.log"));
        System.out.println("输入 /help 查看命令，/tools 查看工具，输入问题开始对话");
    }

    // ---------- 内置命令 ----------

    private static void printHelp() {
        System.out.println("""

                可用命令:
                  /help           显示此帮助
                  /tools          查看已注册的工具及其说明
                  /skills         查看已发现的 Skills
                  /skill reload   重新扫描 Skills
                  /skill on|off <name>  启用或禁用 Skill
                  /plan <任务>    使用 Plan-and-Execute 模式执行复杂任务
                  /save <事实>    保存关键信息到长期记忆（跨会话保留）
                  /memory         查看长期记忆
                  /memory clear   清空长期记忆
                  /clear          清空对话历史
                  quit/exit       退出程序

                使用方式:
                  > 帮我读 pom.xml                ← ReAct 模式（默认）
                  > /plan 创建一个 demo 项目      ← Plan 模式
                  > /save 用户偏好 Java 17        ← 记住偏好，下次自动关联
                  > 项目里有哪些 Java 文件""");
    }

    private static void printTools(ToolRegistry registry) {
        System.out.println("\n已注册的工具（" + registry.names().size() + " 个）:");
        for (String name : registry.names()) {
            var tool = registry.get(name);
            System.out.println("  " + name + " — " + tool.description());
        }
        System.out.println();
    }

    // ---------- 配置加载 ----------

    private static Map<String, String> loadEnv() {
        Map<String, String> map = new HashMap<>();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        map.put(line.substring(0, eq).strip(),
                                line.substring(eq + 1).strip());
                    }
                }
            } catch (IOException ignored) {}
        }
        return map;
    }

    /**
     * 系统环境变量优先于 .env，便于在部署环境覆盖本地配置。
     */
    private static String configValue(Map<String, String> fileEnv,
                                      String key, String defaultValue) {
        String systemValue = System.getenv(key);
        if (systemValue != null) return systemValue.strip();
        String fileValue = fileEnv.get(key);
        return fileValue == null ? defaultValue : fileValue.strip();
    }

    private static boolean configFlag(Map<String, String> fileEnv,
                                      String key, boolean defaultValue) {
        return switch (configValue(
                fileEnv, key, Boolean.toString(defaultValue)).toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }
}
