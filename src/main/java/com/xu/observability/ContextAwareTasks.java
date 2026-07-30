package com.xu.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 在线程池边界传播 OpenTelemetry Context 和 MDC。
 *
 * <p>必须在提交任务的线程中调用 {@code wrap}；如果进入工作线程后再捕获，
 * 得到的已经是工作线程自己的空上下文。</p>
 */
public final class ContextAwareTasks {

    private ContextAwareTasks() {
    }

    /**
     * 在提交线程捕获上下文，返回可在线程池中安全执行的 Runnable。
     * 调用该方法本身不会执行任务。
     */
    public static Runnable wrap(Runnable task) {
        // 这里运行在提交线程，因此捕获到的是父任务的 Span 和 MDC。
        Context context = Context.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> runWithContext(context, mdc, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 在提交线程捕获上下文，返回可供 CompletableFuture 使用的 Supplier。
     */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Context context = Context.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> runWithContext(context, mdc, task);
    }

    private static <T> T runWithContext(
            Context context,
            Map<String, String> capturedMdc,
            Supplier<T> task) {
        Map<String, String> workerMdc = MDC.getCopyOfContextMap();
        try (Scope ignored = context.makeCurrent()) {
            // 工作线程执行期间，后续 Span 和日志都归属提交线程的父链路。
            replaceMdc(capturedMdc);
            return task.get();
        } finally {
            // 线程池会复用线程，必须恢复原值，不能让本次任务污染下一次任务。
            replaceMdc(workerMdc);
        }
    }

    private static void replaceMdc(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(values);
        }
    }
}
