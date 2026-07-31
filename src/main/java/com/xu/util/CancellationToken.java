package com.xu.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared cancellation state for one foreground Agent or Plan run.
 *
 * <p>Thread interruption alone cannot instantly reach Plan worker threads.
 * Every execution boundary therefore observes this shared token as well.</p>
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

    /**
     * Binds a reused executor thread to the generation that was just started.
     * Plan worker threads inherit this value automatically.
     */
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

    /** Creates an independently cancellable scope linked to this run. */
    public CancellationToken childScope() {
        return new CancellationToken(this);
    }

    /** Prevents a new prompt if an old mutating worker could still be alive. */
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
