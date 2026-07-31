package com.xu.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe event queue used by the TUI.
 *
 * <p>Plan workers may publish concurrently, but only the TUI thread consumes
 * and renders events, preventing interleaved ANSI output.</p>
 */
public final class QueueUiEventSink implements UiEventSink {

    private final Deque<UiEvent> queue = new ArrayDeque<>();

    @Override
    public synchronized void emit(UiEvent event) {
        if (event == null) {
            return;
        }
        /*
         * Streaming callbacks may produce hundreds of tiny chunks per second.
         * Coalesce adjacent deltas without crossing a tool/status boundary.
         */
        if (event instanceof UiEvent.AssistantDelta delta
                && queue.peekLast() instanceof UiEvent.AssistantDelta previous
                && previous.taskLabel().equals(delta.taskLabel())) {
            queue.pollLast();
            queue.offerLast(new UiEvent.AssistantDelta(
                    delta.taskLabel(), previous.text() + delta.text()));
            notifyAll();
            return;
        }
        queue.offerLast(event);
        notifyAll();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    public synchronized UiEvent poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        while (queue.isEmpty() && remaining > 0L) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return queue.pollFirst();
    }

    public synchronized UiEvent poll() {
        return queue.pollFirst();
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }
}
