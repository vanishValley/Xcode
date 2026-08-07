package com.xu.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次前台 Agent 或 Plan 任务共享的取消状态。
 *
 * <p>线程中断无法立即传递到所有 Plan Worker，因此每个执行边界还必须检查此令牌。</p>
 */
public final class CancellationToken {

    private final CancellationToken parent;
    private final AtomicLong activeGeneration = new AtomicLong();
    private final AtomicLong cancelledGeneration =
            new AtomicLong(-1L);
    private final InheritableThreadLocal<Long> boundGeneration =
            new InheritableThreadLocal<>() {
                @Override
                protected Long initialValue() {
                    return activeGeneration.get();
                }
            };
    private final AtomicBoolean reusable = new AtomicBoolean(true);

    public CancellationToken() {
        this(null);
    }

    private CancellationToken(CancellationToken parent) {
        this.parent = parent;
    }

    public void beginRun() {
        if (!isReusable()) {
            throw new IllegalStateException(
                    "A previous worker did not terminate safely");
        }
        long generation = activeGeneration.incrementAndGet();
        boundGeneration.set(generation);
    }

    /** 将复用的执行器线程绑定到本次 generation；Plan Worker 会自动继承该值。 */
    public void bindCurrentRun() {
        boundGeneration.set(activeGeneration.get());
    }

    public void cancel() {
        cancelledGeneration.set(activeGeneration.get());
    }

    public boolean isCancelled() {
        long bound = boundGeneration.get();
        return bound != activeGeneration.get()
                || cancelledGeneration.get() == bound
                || (parent != null && parent.isCancelled());
    }

    /** 创建与当前任务关联、但可单独取消的子作用域。 */
    public CancellationToken childScope() {
        return new CancellationToken(this);
    }

    /** 旧的可变 Worker 可能仍在运行时，禁止接受下一条任务。 */
    public void markUnsafeToReuse() {
        reusable.set(false);
        if (parent != null) {
            parent.markUnsafeToReuse();
        }
    }

    public boolean isReusable() {
        return reusable.get()
                && (parent == null || parent.isReusable());
    }

    public void throwIfCancellationRequested()
            throws InterruptedException {
        if (isCancelled()
                || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Task cancelled");
        }
    }
}
