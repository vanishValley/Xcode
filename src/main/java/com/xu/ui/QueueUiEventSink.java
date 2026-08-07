package com.xu.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * TUI 使用的线程安全事件队列。
 *
 * <p>Plan Worker 可以并发发布事件，但只由 TUI 线程消费和渲染，避免 ANSI 输出交错。</p>
 */
public final class QueueUiEventSink implements UiEventSink {

    private final Deque<UiEvent> queue = new ArrayDeque<>();

    @Override
    public synchronized void emit(UiEvent event) {
        if (event == null) {
            return;
        }
        /* 流式回调会产生大量小片段；只合并相邻文本增量，不跨越工具或状态事件。 */
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
