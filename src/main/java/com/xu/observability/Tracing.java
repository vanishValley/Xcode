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
 * 整个应用使用的可观测性总入口。
 *
 * <p>程序启动时创建一个 Tracing，然后把它传给 Agent、LLM、Tool、MCP 等组件。
 * 业务组件通过它创建 Span、记录指标以及保存失败任务的诊断文件，不需要自己
 * 初始化 OpenTelemetry。</p>
 *
 * <p>Tracing 可以被多个线程共享；单次操作返回的 {@link TraceScope} 只能在
 * 创建它的线程中使用。没有配置 Jaeger 时 Span 不会向外发送，Agent 仍可运行。</p>
 */
public final class Tracing implements AutoCloseable {

    private static final Tracing NOOP = new Tracing(
            null,
            null,
            AgentMetrics.noop(),
            ExecutionArtifactStore.disabled(), null);

    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;
    private final AgentMetrics metrics;
    private final ExecutionArtifactStore artifacts;
    private final ExecutionRecorder recorder;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 组装追踪 SDK、指标记录器和诊断文件存储；实例统一由工厂方法创建。
     */
    private Tracing(
            OpenTelemetrySdk sdk,
            Tracer tracer,
            AgentMetrics metrics,
            ExecutionArtifactStore artifacts, ExecutionRecorder recorder) {
        this.sdk = sdk;
        this.tracer = tracer;
        this.metrics = metrics;
        this.artifacts = artifacts;
        this.recorder = recorder;
    }

    /**
     * 程序启动时调用，使用默认目录创建全局 Tracing。
     *
     * <p>默认目录是 {@code ~/.xcode/observability}；也可以通过系统属性
     * {@code xcode.observability.dir} 修改。没有配置 exporter 时不会向 Jaeger
     * 发送 Span，但日志仍然可以携带 trace_id 和 span_id。</p>
     *
     * @return 应用进程内共享的可观测性入口
     */
    public static Tracing create() {
        String configured = System.getProperty("xcode.observability.dir");
        Path root = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"),
                        ".xcode", "observability")
                : Path.of(configured);
        return create(root);
    }

    /**
     * 程序启动时调用，在指定目录保存失败任务的诊断文件并初始化 OpenTelemetry。
     *
     * @param root 诊断文件的根目录
     * @return 可传给各业务组件共用的 Tracing
     */
    public static Tracing create(Path root) {
        return create(root, false);
    }

    /** 本地评测保留完整采集，不依赖 Jaeger；使用同一套生产埋点。 */
    public static Tracing forEvaluation(Path root) {
        return create(root, true);
    }

    private static Tracing create(Path root, boolean evaluation) {
        // 只有用户没有显式配置时才使用默认值，避免覆盖 IDEA 或环境变量配置。
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

        // OpenTelemetry 会自动读取 OTEL_* 环境变量和 otel.* 系统属性。
        ExecutionArtifactStore artifacts = evaluation
                ? ExecutionArtifactStore.create(root, ExecutionArtifactStore.Mode.ALWAYS)
                : ExecutionArtifactStore.create(root);
        ExecutionRecorder recorder = new ExecutionRecorder(artifacts);
        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> defaults)
                .addTracerProviderCustomizer((builder, properties) -> {
                    builder.addSpanProcessor(recorder);
                    if (evaluation) builder.setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn());
                    return builder;
                })
                .build()
                .getOpenTelemetrySdk();
        return new Tracing(
                sdk,
                sdk.getTracer("com.xu.xcode-agent"),
                new AgentMetrics(sdk.getMeter("com.xu.xcode-agent")),
                artifacts, recorder);
    }

    /**
     * 在不需要可观测性的旧入口或单元测试中调用，返回什么都不记录的实现。
     *
     * <p>业务代码仍可以正常调用 start()、metrics() 等方法，因此不用到处写
     * {@code if (tracing != null)}，也不会覆盖线程中已有的 Context 或 MDC。</p>
     */
    public static Tracing noop() {
        return NOOP;
    }

    /** 测试代码调用：使用内存 SDK 创建 Tracing，便于断言生成的 Span。 */
    static Tracing from(OpenTelemetrySdk sdk) {
        return new Tracing(
                sdk,
                sdk.getTracer("com.xu.xcode-agent-test"),
                new AgentMetrics(sdk.getMeter("com.xu.xcode-agent-test")),
                ExecutionArtifactStore.disabled(), null);
    }

    /** 返回指标记录器，供 Agent、LLM 和 Tool 在操作结束时记录次数与耗时。 */
    public AgentMetrics metrics() {
        return metrics;
    }

    /** 返回诊断文件存储，供任务和外部调用保存请求、响应等大文本。 */
    public ExecutionArtifactStore artifacts() {
        return artifacts;
    }

    /** 日常任务和评测均可订阅一条执行 Trace 的原始数据及汇总。 */
    public ExecutionCapture capture(String mode, String input) {
        if (recorder == null || artifacts.mode() == ExecutionArtifactStore.Mode.OFF)
            throw new IllegalStateException("Capture requires enabled Tracing and artifact storage");
        return recorder.capture(start("execution.capture"), mode, input);
    }

    /**
     * 业务代码进入一个应用内部操作时调用，例如 coding.task、agent.turn、tool.execute。
     *
     * @param spanName 固定的操作名称，不要把用户输入或任务 ID 拼进名称
     * @return 当前操作的作用域，必须用 try-with-resources 自动关闭
     */
    public TraceScope start(String spanName) {
        return open(spanName, SpanKind.INTERNAL);
    }

    /**
     * 业务代码准备调用外部服务时调用，例如 llm.chat 或 mcp.call。
     * CLIENT 类型可以让 Jaeger 明确看出这是一次出站调用。
     *
     * @param spanName 固定的外部调用名称
     * @return 当前外部调用的作用域，必须用 try-with-resources 自动关闭
     */
    public TraceScope startClient(String spanName) {
        return open(spanName, SpanKind.CLIENT);
    }

    /**
     * start() 和 startClient() 共用的创建逻辑：根据开关返回真实或空 TraceScope。
     */
    private TraceScope open(String spanName, SpanKind kind) {
        if (tracer == null) {
            return TraceScope.noop();
        }
        return TraceScope.open(tracer, spanName, kind);
    }

    /**
     * 程序退出时调用，先等待尚未发送的 Span 和指标刷新完成，再关闭 SDK。
     * 方法可以重复调用，正常退出和 shutdown hook 同时触发也只会真正关闭一次。
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
