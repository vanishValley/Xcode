package com.xu.eval;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 进程级 cwd 隔离与超时；不通过修改全局 user.dir 模拟工作目录。 */
final class EvalProcess {
    record Exit(int code, boolean timedOut) {}
    static String jdk(String command) {
        return Path.of(System.getProperty("java.home"), "bin", command
                + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }
    static String classpath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }
    static Exit run(List<String> command, Path cwd, Path log, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // 生成代码的测试进程不需要模型凭据。
        builder.environment().remove("DEEPSEEK_API_KEY");
        Process process = builder.start();
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                return new Exit(process.exitValue(), false);
            stop(process);
            return new Exit(-1, true);
        } catch (InterruptedException e) {
            stop(process);
            throw e;
        }
    }
    static void stop(Process process) throws InterruptedException {
        var children = process.descendants().toList();
        children.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }
}
