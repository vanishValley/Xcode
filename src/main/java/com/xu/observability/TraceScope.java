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
 * 表示一次正在执行的链路操作，例如一次 LLM 调用或一次工具调用。
 *
 * <p>创建对象时，它会把新 Span 设为当前节点，并把链路 ID 放入 MDC；
 * 关闭对象时，它会结束 Span，并恢复进入该操作前的上下文。业务代码应当把它
 * 放在 try-with-resources 中，避免异常或提前返回导致链路没有正常结束。</p>
 */
public final class TraceScope implements AutoCloseable {

    private final Span span;
    private final Scope contextScope;
    private final String previousTraceId;
    private final String previousSpanId;
    private final long startedNanos;
    private boolean closed;

    /**
     * 创建一个不真正记录 Span 的空作用域。
     * Tracing 关闭时仍返回该对象，让业务代码不需要判断追踪是否启用。
     */
    private TraceScope() {
        this.span = null;
        this.contextScope = null;
        this.previousTraceId = null;
        this.previousSpanId = null;
        this.startedNanos = System.nanoTime();
    }

    /**
     * 保存本次 Span 以及进入它之前的日志上下文，供 close() 统一结束和恢复。
     */
    private TraceScope(Span span, Scope contextScope,
                       String previousTraceId, String previousSpanId) {
        this.span = span;
        this.contextScope = contextScope;
        this.previousTraceId = previousTraceId;
        this.previousSpanId = previousSpanId;
        this.startedNanos = System.nanoTime();
    }

    /**
     * 当链路追踪未启用时调用，返回一个不会创建 Span、不会修改 MDC 的作用域。
     */
    static TraceScope noop() {
        // 每次都创建新对象，这样 elapsedMillis() 仍从本次业务操作开始计时。
        return new TraceScope();
    }

    /**
     * 创建一个 Span，把它设为当前节点，并让当前线程的日志自动带上链路 ID。
     *
     * <p>该方法由 {@link Tracing#start(String)} 或
     * {@link Tracing#startClient(String)} 间接调用，业务代码通常不直接调用它。</p>
     *
     * @param tracer OpenTelemetry 的 Span 创建器
     * @param name 本次操作名称，例如 {@code agent.turn} 或 {@code llm.chat}
     * @param kind 操作类型：应用内部操作为 INTERNAL，调用外部服务为 CLIENT
     * @return 当前操作的作用域，关闭时会结束 Span 并恢复父节点上下文
     */
    static TraceScope open(Tracer tracer, String name, SpanKind kind) {
        // 先记住父节点的日志 ID；当前节点结束后，日志还要继续归属父节点。
        String previousTraceId = MDC.get("trace_id");
        String previousSpanId = MDC.get("span_id");

        // Tracer 创建 Span。它会读取当前 Context，自动确定这个 Span 的父节点。
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .startSpan();

        // 仅创建 Span 还不够；设为 current 后，后续新建的 Span 才会成为它的子节点。
        Scope contextScope = span.makeCurrent();

        // OpenTelemetry 管 Span 父子关系，MDC 让 SLF4J 业务日志带上同一组链路 ID。
        SpanContext context = span.getSpanContext();
        if (context.isValid()) {
            MDC.put("trace_id", context.getTraceId());
            MDC.put("span_id", context.getSpanId());
        }
        return new TraceScope(
                span, contextScope, previousTraceId, previousSpanId);
    }

    /**
     * 给当前 Span 添加一个字符串属性，供 Jaeger 查看本次操作的业务信息。
     * 返回当前对象是为了支持连续调用多个 attribute()。
     */
    public TraceScope attribute(String key, String value) {
        if (span != null && value != null) span.setAttribute(key, value);
        return this;
    }

    /** 给当前 Span 添加一个整数属性，例如输入字符数、Token 数或重试次数。 */
    public TraceScope attribute(String key, long value) {
        if (span != null) span.setAttribute(key, value);
        return this;
    }

    /** 给当前 Span 添加一个布尔属性，例如本次操作是否发生过降级。 */
    public TraceScope attribute(String key, boolean value) {
        if (span != null) span.setAttribute(key, value);
        return this;
    }

    /**
     * 记录当前操作中发生的一个瞬时事件，例如开始重试；它不会创建子 Span。
     */
    public void event(String name) {
        if (span != null) span.addEvent(name);
    }

    /** 记录一个瞬时事件，并附加该事件自己的补充属性。 */
    public void event(String name, Attributes attributes) {
        if (span != null) span.addEvent(name, attributes);
    }

    /**
     * 在捕获 Java 异常时调用，把异常详情和 ERROR 状态记录到当前 Span。
     * 该方法只负责记录，不会抛出异常，也不会决定业务是否继续执行。
     */
    public void fail(Throwable error) {
        if (span == null || error == null) return;
        span.recordException(error);
        span.setStatus(StatusCode.ERROR, safeMessage(error));
    }

    /**
     * 在没有 Throwable 的失败场景调用，例如接口返回错误码或响应格式错误。
     */
    public void error(String type, String description) {
        if (span == null) return;
        if (type != null) span.setAttribute("error.type", type);
        span.setStatus(StatusCode.ERROR,
                description == null ? "" : description);
    }

    /** 返回整条调用链共享的 trace_id；追踪关闭时返回空字符串。 */
    public String traceId() {
        return spanContext().isValid() ? spanContext().getTraceId() : "";
    }

    /** 返回当前这个 Span 独有的 span_id；追踪关闭时返回空字符串。 */
    public String spanId() {
        return spanContext().isValid() ? spanContext().getSpanId() : "";
    }

    /** 返回当前操作从创建到现在的毫秒耗时，供汇总日志和指标记录使用。 */
    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos);
    }

    /** 统一取得当前 SpanContext，空作用域返回无效上下文。 */
    private SpanContext spanContext() {
        return span == null
                ? SpanContext.getInvalid()
                : span.getSpanContext();
    }

    /**
     * 在 try-with-resources 结束时自动调用：退出当前节点、结束 Span、恢复父节点日志 ID。
     */
    @Override
    public void close() {
        if (span == null || closed) return;
        closed = true;

        // OpenTelemetry 和 MDC 都要恢复，否则后面的 Span、日志会错误归属当前节点。
        contextScope.close();
        span.end();
        restoreMdc("trace_id", previousTraceId);
        restoreMdc("span_id", previousSpanId);
    }

    /** 把一个 MDC 字段恢复到进入当前 Span 前的值。 */
    private static void restoreMdc(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }

    /** 优先使用异常消息；异常没有消息时使用异常类名，避免 ERROR 描述为空。 */
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
