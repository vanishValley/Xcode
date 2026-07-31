package com.xu.cli;

import com.xu.agent.Agent;
import com.xu.agent.PlanExecuteAgent;
import com.xu.config.XcodePaths;
import com.xu.hitl.HitlHandler;
import com.xu.hitl.HitlToolRegistry;
import com.xu.hitl.TerminalHitlHandler;
import com.xu.llm.LlmClient;
import com.xu.mcp.ChromeMcpClient;
import com.xu.memory.LongTermMemory;
import com.xu.memory.MemoryManager;
import com.xu.memory.SessionStore;
import com.xu.observability.Tracing;
import com.xu.plan.PlanStore;
import com.xu.skill.SkillRegistry;
import com.xu.skill.SkillStateStore;
import com.xu.tool.impl.ExecuteCommandTool;
import com.xu.tool.impl.GlobFilesTool;
import com.xu.tool.impl.ListDirTool;
import com.xu.tool.impl.ReadFileTool;
import com.xu.tool.impl.WebFetchTool;
import com.xu.tool.impl.WebSearchTool;
import com.xu.tool.impl.WriteFileTool;
import com.xu.ui.PlainUiEventSink;
import com.xu.ui.QueueUiEventSink;
import com.xu.ui.SafeDisplay;
import com.xu.ui.TuiHitlHandler;
import com.xu.ui.UiEvent;
import com.xu.ui.UiEventSink;
import com.xu.ui.tui.TuiApplication;
import com.xu.util.CancellationToken;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

/** Process bootstrap and UI mode selection. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        XcodePaths paths =
                new XcodePaths(Path.of(System.getProperty("user.dir")));
        Map<String, String> env = loadEnv();
        configureLogging(paths, env);

        if (hasArg(args, "--help") || hasArg(args, "-h")) {
            System.out.println("""
                    Xcode Agent

                    用法:
                      java -jar Xcode-1.0-SNAPSHOT.jar [--ui=auto|tui|plain]

                    选项:
                      --tui, --ui=tui      强制尝试 JLine TUI
                      --plain, --ui=plain  使用无 ANSI 的兼容 CLI
                      --ui=auto            自动选择（默认）
                      -h, --help           显示此帮助
                    """);
            return;
        }

        try (Tracing tracing = Tracing.create()) {
            run(args, tracing, paths, env);
        }
    }

    private static void run(
            String[] args,
            Tracing tracing,
            XcodePaths paths,
            Map<String, String> env) throws Exception {
        try {
            runOnce(args, tracing, paths, env);
        } catch (TuiInitializationException error) {
            /*
             * No user task has started yet: all runtime resources from the
             * failed rich-mode attempt have already been closed by runOnce,
             * so rebuilding a plain stack cannot duplicate side effects.
             */
            logger().atWarn()
                    .addKeyValue("event", "tui.application_initialize_failed")
                    .setCause(error.getCause())
                    .log("TUI 组件初始化失败，回退 plain");
            System.out.println(
                    "[warning] TUI 初始化失败，已自动切换到 plain 模式。");
            runOnce(
                    new String[]{"--ui=plain"},
                    tracing,
                    paths,
                    env);
        }
    }

    private static void runOnce(
            String[] args,
            Tracing tracing,
            XcodePaths paths,
            Map<String, String> env) throws Exception {
        String apiKey = System.getenv().getOrDefault(
                "DEEPSEEK_API_KEY", env.get("DEEPSEEK_API_KEY"));
        String model = System.getenv().getOrDefault(
                "DEEPSEEK_MODEL",
                env.getOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));
        SafeDisplay.registerSecret(apiKey);

        RequestedUi requestedUi = RequestedUi.parse(args);
        Terminal terminal = openTerminal(requestedUi);
        boolean rich = terminal != null;
        PrintWriter plainWriter = new PrintWriter(
                System.out, true, Charset.defaultCharset());

        if (apiKey == null || apiKey.isBlank()) {
            PrintWriter writer = rich ? terminal.writer() : plainWriter;
            writer.println("缺少 DEEPSEEK_API_KEY。");
            writer.println("请在系统环境变量或项目 .env 中配置后重试。");
            writer.flush();
            if (terminal != null) {
                terminal.close();
            }
            return;
        }

        Scanner scanner = rich
                ? null : new Scanner(System.in, Charset.defaultCharset());
        try {
        QueueUiEventSink eventQueue =
                rich ? new QueueUiEventSink() : null;
        UiEventSink events = rich
                ? eventQueue : new PlainUiEventSink(plainWriter);
        TuiHitlHandler tuiHitl =
                rich ? new TuiHitlHandler(events) : null;
        HitlHandler hitl = rich
                ? tuiHitl
                : new TerminalHitlHandler(scanner, plainWriter);

        if (rich) {
            terminal.writer().print("Xcode Agent · 正在初始化…");
            terminal.writer().flush();
        } else {
            plainWriter.println("Xcode Agent  |  " + model);
            plainWriter.println("输入 /help 查看命令，exit 退出。");
        }
        if (!rich && requestedUi != RequestedUi.PLAIN) {
            events.emit(new UiEvent.Notice(
                    UiEvent.Severity.WARNING,
                    "当前终端不支持交互能力，已自动切换到 plain 模式。"));
        }

        CancellationToken cancellation = new CancellationToken();
        LlmClient client = new LlmClient(apiKey, model, tracing);
        HitlToolRegistry registry =
                new HitlToolRegistry(hitl, cancellation);
        registry.setEnabled(configFlag(env, "HITL_ENABLED", true));
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool(paths.projectRoot()));
        registry.register(new ListDirTool());
        registry.register(new ExecuteCommandTool());
        registry.register(new GlobFilesTool(paths.projectRoot()));

        paths.initProjectIfNeeded();
        String projectPath = paths.projectRoot().toString();
        Path projectDataDir = paths.projectDataDir();
        SessionStore sessionStore = new SessionStore(paths);
        LongTermMemory longTermMemory =
                LongTermMemory.create(projectDataDir);
        MemoryManager memory = new MemoryManager(
                sessionStore, longTermMemory, client, projectPath);
        PlanStore planStore = new PlanStore(projectDataDir);

        SkillRegistry skills = new SkillRegistry(paths);
        skills.reload();
        SkillStateStore skillStates = new SkillStateStore(
                paths.userDir().resolve("skills.json"));
        SkillStateStore.DISABLED_HOLDER = skillStates.disabledNames();
        registry.registerLoadSkillTool(skills);

        String searchApiKey = configValue(env, "WSA_API_KEY", "");
        SafeDisplay.registerSecret(searchApiKey);
        if (searchApiKey.isBlank()) {
            events.emit(new UiEvent.Notice(
                    UiEvent.Severity.INFO,
                    "未配置 WSA_API_KEY，web_search 未启用；web_fetch 仍可用。"));
        } else {
            registry.register(new WebSearchTool(searchApiKey));
        }
        registry.register(new WebFetchTool());

        ChromeMcpClient chromeMcp = startChromeMcp(
                registry, env, paths.projectRoot(), tracing, events);
        try {
            Agent agent = new Agent(
                    client,
                    registry,
                    memory,
                    skills,
                    "main",
                    tracing,
                    events,
                    cancellation);
            PlanExecuteAgent planAgent = new PlanExecuteAgent(
                    client,
                    registry,
                    planStore,
                    longTermMemory,
                    projectPath,
                    skills,
                    tracing,
                    events,
                    cancellation);
            CommandProcessor commands = new CommandProcessor(
                    agent,
                    planAgent,
                    registry,
                    memory,
                    planStore,
                    skills,
                    skillStates,
                    model,
                    paths.projectRoot(),
                    Path.of(System.getProperty("xcode.log.dir"))
                            .resolve("xcode.log"));

            if (rich) {
                terminal.writer().print('\r');
                terminal.puts(InfoCmp.Capability.clr_eol);
                terminal.flush();
                TuiApplication application;
                try {
                    application = new TuiApplication(
                            terminal,
                            eventQueue,
                            tuiHitl,
                            commands,
                            skills,
                            paths.projectRoot(),
                            projectDataDir.resolve("input_history"),
                            registry.names().size(),
                            skills.allSkills().size(),
                            cancellation,
                            client::cancelActiveRequests);
                } catch (IOException | RuntimeException error) {
                    throw new TuiInitializationException(error);
                }
                Thread shutdownHook = new Thread(
                        application::close,
                        "xcode-tui-shutdown");
                boolean hookRegistered = false;
                try {
                    try {
                        Runtime.getRuntime().addShutdownHook(shutdownHook);
                        hookRegistered = true;
                    } catch (SecurityException error) {
                        logger().atWarn()
                                .addKeyValue(
                                        "event",
                                        "tui.shutdown_hook_unavailable")
                                .log("无法注册 TUI shutdown hook");
                    }
                    try (application) {
                        application.run();
                    }
                } finally {
                    if (hookRegistered) {
                        try {
                            Runtime.getRuntime().removeShutdownHook(
                                    shutdownHook);
                        } catch (IllegalStateException ignored) {
                            // JVM shutdown is already running this hook.
                        }
                    }
                }
            } else {
                try (scanner) {
                    new PlainCliApplication(
                            scanner,
                            plainWriter,
                            commands,
                            cancellation).run();
                }
            }
        } finally {
            if (chromeMcp != null) {
                chromeMcp.close();
            }
        }
        } finally {
            if (scanner != null) {
                scanner.close();
            }
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class TuiInitializationException
            extends Exception {
        private TuiInitializationException(Throwable cause) {
            super(cause);
        }
    }

    private static ChromeMcpClient startChromeMcp(
            HitlToolRegistry registry,
            Map<String, String> env,
            Path projectRoot,
            Tracing tracing,
            UiEventSink events) {
        if (!configFlag(env, "CHROME_MCP_ENABLED", true)) {
            return null;
        }
        try {
            ChromeMcpClient client = ChromeMcpClient.start(
                    registry, env, projectRoot, tracing);
            events.emit(new UiEvent.Notice(
                    UiEvent.Severity.SUCCESS,
                    "Chrome DevTools MCP 已连接，注册 "
                            + client.registeredToolCount() + " 个工具。"));
            return client;
        } catch (IOException error) {
            events.emit(new UiEvent.Notice(
                    UiEvent.Severity.WARNING,
                    "Chrome DevTools MCP 不可用；本地工具不受影响。"));
            logger().atWarn()
                    .addKeyValue("event", "mcp.chrome.start_failed")
                    .setCause(error)
                    .log("Chrome MCP 启动失败");
            return null;
        }
    }

    private static Terminal openTerminal(RequestedUi requested) {
        if (requested == RequestedUi.PLAIN) {
            return null;
        }
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .name("Xcode Agent")
                    .system(true)
                    .nativeSignals(true)
                    .dumb(true)
                    .color(System.getenv("NO_COLOR") == null)
                    .build();
            if (Terminal.TYPE_DUMB.equals(terminal.getType())
                    || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())) {
                terminal.close();
                return null;
            }
            return terminal;
        } catch (Exception error) {
            logger().atWarn()
                    .addKeyValue("event", "tui.initialize_failed")
                    .setCause(error)
                    .log("TUI 初始化失败，回退 plain");
            return null;
        }
    }

    private enum RequestedUi {
        AUTO, TUI, PLAIN;

        static RequestedUi parse(String[] args) {
            for (String arg : args) {
                String normalized = arg.toLowerCase(Locale.ROOT);
                if ("--plain".equals(normalized)
                        || "--ui=plain".equals(normalized)) {
                    return PLAIN;
                }
                if ("--tui".equals(normalized)
                        || "--ui=tui".equals(normalized)) {
                    return TUI;
                }
            }
            return AUTO;
        }
    }

    private static boolean hasArg(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

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

    private static Logger logger() {
        return LoggerFactory.getLogger(Main.class);
    }

    private static Map<String, String> loadEnv() {
        Map<String, String> map = new HashMap<>();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try {
                for (String rawLine : Files.readAllLines(envFile)) {
                    String line = rawLine.strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        map.put(
                                line.substring(0, equals).strip(),
                                line.substring(equals + 1).strip());
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return map;
    }

    private static String configValue(
            Map<String, String> fileEnv,
            String key,
            String defaultValue) {
        String systemValue = System.getenv(key);
        if (systemValue != null) {
            return systemValue.strip();
        }
        String fileValue = fileEnv.get(key);
        return fileValue == null ? defaultValue : fileValue.strip();
    }

    private static boolean configFlag(
            Map<String, String> fileEnv,
            String key,
            boolean defaultValue) {
        return switch (configValue(
                fileEnv,
                key,
                Boolean.toString(defaultValue)).toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }
}
