package com.xu.observability;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用级链路追踪入口。
 *
 * <p>生产代码在进程启动时创建一个实例，再通过构造器共享给各组件。
 * 默认不导出 Span；可通过标准 OpenTelemetry 环境变量切换到 console
 * 或 OTLP，因此未启动 Jaeger 时不会影响 Agent 主流程。</p>
 *
 * <p>该类可以被多线程共享，但 {@link TraceScope} 只代表创建它的当前操作，
 * 不应跨线程复用。</p>
 */
public final class Tracing implements AutoCloseable {

    private static final Tracing NOOP = new Tracing(
            null,
            null,
            AgentMetrics.noop(),
            ExecutionArtifactStore.disabled());

    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;
    private final AgentMetrics metrics;
    private final ExecutionArtifactStore artifacts;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Tracing(
            OpenTelemetrySdk sdk,
            Tracer tracer,
            AgentMetrics metrics,
            ExecutionArtifactStore artifacts) {
        this.sdk = sdk;
        this.tracer = tracer;
        this.metrics = metrics;
        this.artifacts = artifacts;
    }

    /**
     * 使用环境变量和系统属性初始化 OpenTelemetry SDK。
     *
     * <p>没有配置 exporter 时只生成链路上下文，不向外部服务发送数据；
     * 这样本地未部署 Jaeger 等后端时也可以正常运行，并且日志中仍能携带
     * {@code trace_id} 和 {@code span_id}。</p>
     *
     * @return 应用进程内共享的追踪入口
     */
    public static Tracing create() {
        String configured = System.getProperty("xcode.observability.dir");
        Path root = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"),
                        ".xcode", "observability")
                : Path.of(configured);
        return create(root);
    }

    /** 初始化遥测，并将诊断产物保存到 {@code root} 下。 */
    public static Tracing create(Path root) {
        Map<String, String> defaults = new HashMap<>();
        if (System.getenv("OTEL_TRACES_EXPORTER") == null
                && System.getProperty("otel.traces.exporter") == null) {
            defaults.put("otel.traces.exporter", "none");
        }
        if (System.getenv("OTEL_METRICS_EXPORTER") == null
                && System.getProperty("otel.metrics.exporter") == null) {
            defaults.put("otel.metrics.exporter", "none");
        }
        if (System.getenv("OTEL_LOGS_EXPORTER") == null
                && System.getProperty("otel.logs.exporter") == null) {
            defaults.put("otel.logs.exporter", "none");
        }
        if (System.getenv("OTEL_SERVICE_NAME") == null
                && System.getProperty("otel.service.name") == null) {
            defaults.put("otel.service.name", "xcode-agent");
        }

        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> defaults)
                .build()
                .getOpenTelemetrySdk();
        return new Tracing(
                sdk,
                sdk.getTracer("com.xu.xcode-agent"),
                new AgentMetrics(sdk.getMeter("com.xu.xcode-agent")),
                ExecutionArtifactStore.create(root));
    }

    /**
     * 返回不创建 Span 的实现，供旧构造函数和不关心追踪的单元测试使用。
     * 它不会覆盖调用线程中已有的 OpenTelemetry Context 或 MDC。
     */
    public static Tracing noop() {
        return NOOP;
    }

    /** 包可见工厂，仅供本包测试用自定义 SDK 验证 Span。 */
    static Tracing from(OpenTelemetrySdk sdk) {
        return new Tracing(
                sdk,
                sdk.getTracer("com.xu.xcode-agent-test"),
                new AgentMetrics(sdk.getMeter("com.xu.xcode-agent-test")),
                ExecutionArtifactStore.disabled());
    }

    public AgentMetrics metrics() {
        return metrics;
    }

    public ExecutionArtifactStore artifacts() {
        return artifacts;
    }

    /**
     * 创建应用内部操作 Span，例如 {@code coding.task}、{@code agent.turn}
     * 和 {@code tool.execute}。
     *
     * @param spanName 稳定、低基数的操作名称
     * @return 需要通过 try-with-resources 关闭的 Span 作用域
     */
    public TraceScope start(String spanName) {
        return open(spanName, SpanKind.INTERNAL);
    }

    /**
     * 创建调用外部依赖的 Client Span，例如 {@code llm.chat} 和
     * {@code mcp.call}。
     *
     * @param spanName 稳定、低基数的外部调用名称
     * @return 需要通过 try-with-resources 关闭的 Span 作用域
     */
    public TraceScope startClient(String spanName) {
        return open(spanName, SpanKind.CLIENT);
    }

    private TraceScope open(String spanName, SpanKind kind) {
        if (tracer == null) {
            return TraceScope.noop();
        }
        return TraceScope.open(tracer, spanName, kind);
    }

    /**
     * 刷新并关闭 SDK。方法可重复调用，方便正常退出和 shutdown hook 共用。
     */
    @Override
    public void close() {
        if (sdk == null || !closed.compareAndSet(false, true)) {
            return;
        }
        sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
        sdk.getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
        sdk.getSdkTracerProvider().shutdown().join(5, TimeUnit.SECONDS);
        sdk.getSdkMeterProvider().shutdown().join(5, TimeUnit.SECONDS);
    }
}
