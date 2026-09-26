package com.xu.team;

import com.xu.ui.SafeDisplay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** 单写入者保序落盘；不在运行时状态锁内执行磁盘 I/O。不提供崩溃续跑。 */
public final class TeamEventStore implements AutoCloseable {
    private final Path directory;
    private final ThreadPoolExecutor writer;
    private volatile Throwable failure;

    public TeamEventStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory.resolve("results"));
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2048), r -> {
                    Thread t = new Thread(r, "team-journal"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public void event(TeamTypes.Event event) {
        enqueue(() -> Files.writeString(directory.resolve("events.jsonl"),
                SafeDisplay.redact(TeamJson.write(event)) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    }
    public void result(TeamTypes.Result result) {
        enqueue(() -> Files.writeString(directory.resolve("results").resolve(result.resultId() + ".json"),
                SafeDisplay.redact(TeamJson.write(result)), StandardCharsets.UTF_8));
    }
    public void manifest(Object manifest) {
        enqueue(() -> com.xu.util.FileUtils.atomicWrite(directory.resolve("manifest.json"),
                SafeDisplay.redact(TeamJson.write(manifest))));
    }
    public Path directory() { return directory; }
    public String failure() { return failure == null ? "" : failure.getClass().getSimpleName(); }
    private void enqueue(IoAction action) {
        if (failure != null) return;
        try { writer.execute(() -> {
            try { action.run(); } catch (Exception e) { failure = e; }
        }); } catch (RejectedExecutionException e) { failure = e; }
    }
    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                failure = new IOException("Journal drain timed out"); writer.shutdownNow();
            }
        } catch (InterruptedException e) { failure = e; writer.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
}
