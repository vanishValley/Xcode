package com.xu.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 让提交到线程池的异步任务继续属于原来的调用链。
 *
 * <p>OpenTelemetry Context 和 MDC 默认只存在于当前线程，直接切到线程池会丢失
 * trace_id、span_id、task_id。提交任务前用 wrap() 包装，它会在提交线程保存
 * 上下文，在工作线程执行任务前恢复上下文，执行完再清理。</p>
 */
public final class ContextAwareTasks {

    /** 工具类不保存实例状态，不允许创建对象。 */
    private ContextAwareTasks() {
    }

    /**
     * 把无返回值的异步任务连同当前链路上下文一起包装，通常在 executor.submit() 前调用。
     * 调用 wrap() 本身不会执行任务。
     *
     * @param task 原本要提交给线程池的任务
     * @return 带有当前 OpenTelemetry Context 和 MDC 的新任务
     */
    public static Runnable wrap(Runnable task) {
        // wrap() 在提交线程执行，因此这里拿到的是父任务的 Span 和日志字段。
        Context context = Context.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> runWithContext(context, mdc, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 把有返回值的异步任务连同当前链路上下文一起包装，供 CompletableFuture 使用。
     *
     * @param task 原本要异步执行的任务
     * @return 带有当前 OpenTelemetry Context 和 MDC 的 Supplier
     */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Context context = Context.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> runWithContext(context, mdc, task);
    }

    /**
     * 工作线程真正执行任务时调用：装载父上下文，执行任务，再恢复工作线程原状态。
     */
    private static <T> T runWithContext(
            Context context,
            Map<String, String> capturedMdc,
            Supplier<T> task) {
        Map<String, String> workerMdc = MDC.getCopyOfContextMap();
        try (Scope ignored = context.makeCurrent()) {
            // 执行期间新建的 Span 和普通日志都会继续归属提交线程中的父任务。
            replaceMdc(capturedMdc);
            return task.get();
        } finally {
            // 线程池会复用线程，结束后必须还原，不能把本次任务 ID 留给下个任务。
            replaceMdc(workerMdc);
        }
    }

    /** 用给定快照完整替换当前线程的 MDC；空快照表示清空。 */
    private static void replaceMdc(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(values);
        }
    }
}
