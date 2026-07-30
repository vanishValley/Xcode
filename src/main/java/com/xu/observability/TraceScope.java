package com.xu.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;

/**
 * 一个正在执行的链路节点，同时管理 Span、当前 Context 和日志 MDC。
 *
 * <p>统一使用 try-with-resources。关闭子节点时会恢复父节点的 MDC，
 * 因此不能用 {@code MDC.clear()} 代替恢复逻辑。</p>
 */
public final class TraceScope implements AutoCloseable {

    private final Span span;
    private final Scope contextScope;
    private final String previousTraceId;
    private final String previousSpanId;
    private final long startedNanos;
    private boolean closed;

    private TraceScope() {
        this.span = null;
        this.contextScope = null;
        this.previousTraceId = null;
        this.previousSpanId = null;
        this.startedNanos = System.nanoTime();
    }

    private TraceScope(Span span, Scope contextScope,
                       String previousTraceId, String previousSpanId) {
        this.span = span;
        this.contextScope = contextScope;
        this.previousTraceId = previousTraceId;
        this.previousSpanId = previousSpanId;
        this.startedNanos = System.nanoTime();
    }

    static TraceScope noop() {
        // 每次返回独立对象，确保 elapsedMillis() 从本次调用开始计算。
        return new TraceScope();
    }

    static TraceScope open(Tracer tracer, String name, SpanKind kind) {
        // 进入子 Span 前保存父 Span 的日志标识，close() 时需要恢复。
        String previousTraceId = MDC.get("trace_id");
        String previousSpanId = MDC.get("span_id");

        // makeCurrent() 使当前线程内后续创建的 Span 自动成为它的子节点。
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .startSpan();
        Scope contextScope = span.makeCurrent();

        // OpenTelemetry 管调用关系，MDC 只负责让普通业务日志带上链路标识。
        SpanContext context = span.getSpanContext();
        if (context.isValid()) {
            MDC.put("trace_id", context.getTraceId());
            MDC.put("span_id", context.getSpanId());
        }
        return new TraceScope(
                span, contextScope, previousTraceId, previousSpanId);
    }

    /**
     * 给当前 Span 添加字符串属性。
     *
     * <p>返回当前对象是为了支持
     * {@code tracing.start(...).attribute(...).attribute(...)} 的链式写法。</p>
     */
    public TraceScope attribute(String key, String value) {
        if (span != null && value != null) span.setAttribute(key, value);
        return this;
    }

    /** 给当前 Span 添加整数属性，并返回当前对象以支持链式调用。 */
    public TraceScope attribute(String key, long value) {
        if (span != null) span.setAttribute(key, value);
        return this;
    }

    /** 给当前 Span 添加布尔属性，并返回当前对象以支持链式调用。 */
    public TraceScope attribute(String key, boolean value) {
        if (span != null) span.setAttribute(key, value);
        return this;
    }

    /** 在当前 Span 上记录一个瞬时事件，不会创建新的子 Span。 */
    public void event(String name) {
        if (span != null) span.addEvent(name);
    }

    /** 在当前 Span 上记录一个带属性的瞬时事件。 */
    public void event(String name, Attributes attributes) {
        if (span != null) span.addEvent(name, attributes);
    }

    /** 将异常附加到当前 Span；是否继续执行仍由业务调用方决定。 */
    public void fail(Throwable error) {
        if (span == null || error == null) return;
        span.recordException(error);
        span.setStatus(StatusCode.ERROR, safeMessage(error));
    }

    /** 标记没有 Java 异常对象的协议错误或业务错误。 */
    public void error(String type, String description) {
        if (span == null) return;
        if (type != null) span.setAttribute("error.type", type);
        span.setStatus(StatusCode.ERROR,
                description == null ? "" : description);
    }

    public String traceId() {
        return spanContext().isValid() ? spanContext().getTraceId() : "";
    }

    public String spanId() {
        return spanContext().isValid() ? spanContext().getSpanId() : "";
    }

    /** 返回该作用域从创建到当前时刻的耗时，主要用于结构化日志。 */
    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos);
    }

    private SpanContext spanContext() {
        return span == null
                ? SpanContext.getInvalid()
                : span.getSpanContext();
    }

    @Override
    public void close() {
        if (span == null || closed) return;
        closed = true;

        // 先退出当前上下文，再结束 Span，最后把日志标识恢复为父 Span。
        contextScope.close();
        span.end();
        restoreMdc("trace_id", previousTraceId);
        restoreMdc("span_id", previousSpanId);
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
