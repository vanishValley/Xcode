package com.xu.team;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Git 参数直接传进程；不经过 Shell。超时/中断后终止进程树，保留工作区供检查。 */
final class WorkspaceGit {
    record Result(int exitCode, String output) {}

    static Result run(Path directory, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false",
                "-c", "user.name=Xcode", "-c", "user.email=xcode@localhost"));
        command.addAll(List.of(args));
        Path log = Files.createTempFile("xcode-git-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            // 宿主的 Git 定位变量不能把任务命令重定向回另一个 index/仓库。
            for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR"))
                builder.environment().remove(key);
            process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS))
                throw new TeamException("GIT_TIMEOUT", "Git 超时；工作区已保留，需要检查现场");
            if (Files.size(log) > 512 * 1024)
                throw new TeamException("GIT_OUTPUT_LIMIT", "Git 输出超限；不能据此判定操作失败或重放");
            return new Result(process.exitValue(), Files.readString(log).strip());
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Files.deleteIfExists(log);
        }
    }

    static String require(Path directory, String... args) throws IOException, InterruptedException {
        Result result = run(directory, args);
        if (result.exitCode() != 0) throw new TeamException("GIT_FAILED", result.output());
        return result.output();
    }

    static String head(Path directory) throws IOException, InterruptedException {
        return require(directory, "rev-parse", "--verify", "HEAD^{commit}");
    }

    static void clean(Path directory) throws IOException, InterruptedException {
        if (!require(directory, "status", "--porcelain", "--untracked-files=all").isEmpty())
            throw new TeamException("DIRTY_WORKSPACE", "工作区存在未交付修改或未跟踪文件: " + directory);
        if (!require(directory, "ls-files", "-u").isEmpty())
            throw new TeamException("MERGE_CONFLICT", "工作区存在未解决冲突");
        String mergeHead = require(directory, "rev-parse", "--git-path", "MERGE_HEAD");
        if (Files.exists(directory.resolve(mergeHead)))
            throw new TeamException("MERGE_IN_PROGRESS", "需要完成合并提交");
    }

    /** 同 JVM 和跨 JVM 使用同一 OS 文件锁；调用方不能把锁释放视为子进程退出证明。 */
    static Lease lock(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new TeamException("WORKSPACE_BUSY", "资源被其他执行者占用: " + path);
            return new Lease(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new TeamException("WORKSPACE_BUSY", "资源被其他执行者占用: " + path);
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }

    record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
