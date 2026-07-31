package com.xu.tool.impl;

import com.xu.tool.Tool;
import com.xu.tool.ToolExecutionResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 命令执行工具 —— Agent 靠它跑编译、测试、git 等任何命令行操作。
 *
 * 这是所有工具里最"危险"的一个，必须有三层保护：
 *   1. 超时限制（60 秒）：防止死循环 / 挂起的进程永不释放
 *   2. 输出截断（8000 字符）：防止超大输出撑爆 LLM 上下文
 *   3. 黑名单：sudo、rm -rf /、mkfs、dd、fork bomb 等直接拒绝，不交给 OS
 *
 * 底层用 ProcessBuilder（非 Runtime.exec），支持：
 *   - 合并 stderr 到 stdout（2>&1），方便 LLM 一次性看到所有输出
 *   - 在项目根目录执行，行为可预测
 */
public class ExecuteCommandTool implements Tool {

    /** 命令执行超时（秒），超过即强杀进程 */
    private static final int TIMEOUT_SECONDS = 60;
    /** 输出最大字符数，超过则截断并标记 */
    private static final int MAX_OUTPUT_CHARS = 8_000;

    /** 命令黑名单关键字，命中任何一个就直接拒绝 */
    private static final java.util.Set<String> BLACKLIST = java.util.Set.of(
            // 特权提权
            "sudo", "su ",
            // 破坏性删除
            "rm -rf /", "rm -rf ~", "rm -rf .",
            // 格式化 / 清盘
            "mkfs", "dd if=", "dd of=",
            // fork bomb
            ":(){ :|:& };:", "%0|%0",
            // 管道执行远程脚本
            "curl", "wget", "| sh", "| bash",
            // 全盘操作
            "> /dev/sda", "chmod 777 /",
            // 关机重启
            "shutdown", "reboot", "halt", "poweroff"
    );

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String description() {
        return "在当前项目目录执行 Shell 命令。参数：command（要执行的命令字符串）。" +
                "超时 60 秒，输出上限 8000 字符。mvn、javac、git 等常用命令可正常使用。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "要执行的 Shell 命令。例如：mvn compile、git status、javac Main.java"
                        )
                ),
                "required", java.util.List.of("command")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return executeObserved(arguments).content();
    }

    /**
     * 执行命令并返回退出码和超时状态，避免上层从文本中猜测执行结果。
     */
    @Override
    public ToolExecutionResult executeObserved(
            Map<String, Object> arguments) throws Exception {
        // ===== 1. 参数校验 =====
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return ToolExecutionResult.failure(
                    "错误：缺少 command 参数",
                    "INVALID_ARGUMENT");
        }

        // ===== 2. 黑名单检查 =====
        String lowerCmd = command.toLowerCase();
        for (String keyword : BLACKLIST) {
            if (lowerCmd.contains(keyword)) {
                return ToolExecutionResult.failure(
                        "错误：命令包含危险操作（" + keyword
                                + "），已被拦截。如需执行，请手动在终端操作。",
                        "COMMAND_BLOCKED");
            }
        }

        // ===== 3. 执行命令 =====
        // ProcessBuilder 比 Runtime.exec 更好：自动处理引号、环境变量继承、工作目录设置
        ProcessBuilder pb = new ProcessBuilder();
        // Windows 下需要用 cmd /c 包一层，否则直接传字符串没用
        // 简单判断：系统是 Windows 就用 cmd，否则用 sh
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            pb.command("cmd", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }
        pb.directory(new java.io.File("."));  // 工作目录 = 项目根
        pb.redirectErrorStream(true);          // stderr 合并到 stdout

        Process process = pb.start();
        StringBuffer output = new StringBuffer();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicReference<Exception> readError = new AtomicReference<>();

        /*
         * 必须边等待边读取输出。若先 readLine() 到 EOF 再 waitFor()，
         * 无输出且不退出的命令会永远卡在读取阶段，60 秒超时实际上不会生效。
         */
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream(),
                            commandCharset(isWindows)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendWithinLimit(output, line, truncated);
                }
            } catch (Exception error) {
                readError.set(error);
            }
        }, "command-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean timedOut;
        try {
            // 等待进程结束，超时则强杀
            timedOut = !process.waitFor(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (timedOut) {
                destroyProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outputReader.join(5_000);
        } catch (InterruptedException error) {
            destroyProcessTree(process);
            outputReader.interrupt();
            Thread.currentThread().interrupt();
            throw error;
        }

        if (outputReader.isAlive()) {
            try {
                process.getInputStream().close();
            } catch (Exception ignored) {
                // 进程已退出或流已关闭时无需额外处理。
            }
            outputReader.interrupt();
        }
        if (!timedOut && readError.get() != null) {
            throw readError.get();
        }

        // ===== 4. 格式化输出 =====
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        String outputText = output.toString();

        // 拼接结果摘要
        StringBuilder result = new StringBuilder();
        result.append("命令: ").append(command).append("\n");
        result.append("退出码: ").append(exitCode);
        if (timedOut) result.append("（超时，" + TIMEOUT_SECONDS + "秒）");
        result.append("\n\n");
        if (outputText.isEmpty()) {
            result.append("(无输出)");
        } else {
            result.append(outputText);
            if (truncated.get()) {
                result.append("\n... [输出已截断，共超出 " + MAX_OUTPUT_CHARS + " 字符]");
            }
        }
        return ToolExecutionResult.command(
                result.toString(), exitCode, timedOut);
    }

    /**
     * Takes a descendant snapshot before killing the shell. On Windows,
     * terminating only {@code cmd /c} can leave Maven, Java or Node children
     * running after the user presses Ctrl+C.
     */
    private static void destroyProcessTree(Process process) {
        List<ProcessHandle> descendants =
                process.descendants().toList();
        descendants.forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroy();
            }
        });
        process.destroy();
        descendants.forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static Charset commandCharset(boolean windows) {
        String configured = System.getenv("XCODE_COMMAND_CHARSET");
        if (configured != null && !configured.isBlank()) {
            try {
                return Charset.forName(configured.strip());
            } catch (RuntimeException ignored) {
                // Fall back to the platform-safe default below.
            }
        }
        return windows ? Charset.defaultCharset()
                : StandardCharsets.UTF_8;
    }

    /** 持续排空进程输出，但只保留前 MAX_OUTPUT_CHARS 个字符。 */
    private static void appendWithinLimit(
            StringBuffer output,
            String line,
            AtomicBoolean truncated) {
        int remaining = MAX_OUTPUT_CHARS - output.length();
        if (remaining <= 0) {
            truncated.set(true);
            return;
        }

        String text = line + "\n";
        if (text.length() <= remaining) {
            output.append(text);
            return;
        }

        output.append(text, 0, remaining);
        truncated.set(true);
    }
}
