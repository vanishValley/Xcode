package com.xu.observability;

import java.nio.file.Path;
import java.util.Map;

/** 一次任务的采集作用域。关闭后保留汇总快照，解除监听并按原有策略归档。 */
public final class ExecutionCapture implements AutoCloseable {
    private final TraceScope scope;
    private final ExecutionRecorder recorder;
    private final ExecutionArtifactStore artifacts;
    final ExecutionSnapshot.Counter counter = new ExecutionSnapshot.Counter();
    private Path directory;
    private boolean closed;
    private String outcome = "SUCCESS";
    boolean hadErrors;

    ExecutionCapture(TraceScope scope, ExecutionRecorder recorder, ExecutionArtifactStore artifacts,
                     String mode, String input) {
        this.scope = scope; this.recorder = recorder; this.artifacts = artifacts;
        directory = artifacts.beginCapture(scope.traceId(), mode, input);
    }
    public String traceId() { return scope.traceId(); }
    public Path directory() { return directory; }
    public ExecutionSnapshot snapshot() { return counter.snapshot(); }
    public void outcome(String value) { outcome = value; scope.attribute("task.outcome", value); }
    public void event(String type, Object value) {
        recorder.event(this, type, "", Map.of("payload", value));
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        // 先结束最外层 Span，再归档，避免结束事件重新创建 staging 目录。
        scope.close();
        recorder.remove(traceId());
        directory = artifacts.completeCapture(traceId(), outcome, hadErrors);
    }
}
