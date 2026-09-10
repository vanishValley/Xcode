package com.xu.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 监听既有 OpenTelemetry Span；不包装模型、不重新执行工具、不重复埋点。 */
final class ExecutionRecorder implements SpanProcessor {
    private final Map<String,ExecutionCapture> active = new ConcurrentHashMap<>();
    private final ExecutionArtifactStore artifacts;
    ExecutionRecorder(ExecutionArtifactStore artifacts) { this.artifacts = artifacts; }
    ExecutionCapture capture(TraceScope scope, String mode, String input) {
        if (active.containsKey(scope.traceId())) {
            scope.close();
            throw new IllegalStateException("A capture already owns this trace");
        }
        var capture = new ExecutionCapture(scope,this,artifacts,mode,input);
        active.put(scope.traceId(),capture);
        event(capture,"capture.start",mode,Map.of());
        return capture;
    }
    void remove(String traceId) { active.remove(traceId); }
    @Override public boolean isStartRequired() { return true; }
    @Override public boolean isEndRequired() { return true; }
    @Override public void onStart(Context parent, ReadWriteSpan span) {
        var capture = active.get(span.getSpanContext().getTraceId());
        if (capture != null) record(capture,"span.start",span.toSpanData());
    }
    @Override public void onEnd(ReadableSpan span) {
        var capture = active.get(span.getSpanContext().getTraceId());
        if (capture != null) record(capture,"span.end",span.toSpanData());
    }
    private void record(ExecutionCapture capture, String type, io.opentelemetry.sdk.trace.data.SpanData span) {
        Map<String,Object> attributes = new LinkedHashMap<>();
        span.getAttributes().forEach((key,value)->attributes.put(key.getKey(),value));
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("time",java.time.Instant.now().toString());
        event.put("traceId",span.getTraceId()); event.put("spanId",span.getSpanId());
        event.put("parentSpanId",span.getParentSpanId()); event.put("type",type); event.put("name",span.getName());
        event.put("attributes",attributes); event.put("status",span.getStatus().getStatusCode().name());
        event.put("durationNanos",Math.max(0,span.getEndEpochNanos()-span.getStartEpochNanos()));
        synchronized (capture) {
            if (span.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR)
                capture.hadErrors = true;
            capture.counter.accept(type,span.getName(),attributes);
            artifacts.appendEvent(capture.traceId(),event);
        }
    }
    void event(ExecutionCapture capture, String type, String name, Map<String,Object> attributes) {
        synchronized (capture) {
            capture.counter.accept(type,name,attributes);
            artifacts.appendEvent(capture.traceId(),Map.of("time",java.time.Instant.now().toString(),
                    "traceId",capture.traceId(),"type",type,"name",name,"attributes",attributes));
        }
    }
}
