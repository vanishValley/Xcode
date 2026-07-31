package com.xu.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QueueUiEventSinkTest {

    @Test
    void shouldNotLoseConcurrentCriticalEvents() throws Exception {
        QueueUiEventSink sink = new QueueUiEventSink();
        int publishers = 8;
        int each = 500;
        ExecutorService executor = Executors.newFixedThreadPool(publishers);
        for (int worker = 0; worker < publishers; worker++) {
            int id = worker;
            executor.submit(() -> {
                for (int index = 0; index < each; index++) {
                    sink.emit(new UiEvent.Notice(
                            UiEvent.Severity.INFO, id + ":" + index));
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        List<UiEvent> events = new ArrayList<>();
        UiEvent event;
        while ((event = sink.poll()) != null) {
            events.add(event);
        }
        assertEquals(publishers * each, events.size());
    }

    @Test
    void shouldCoalesceOnlyAdjacentStreamingDeltas() {
        QueueUiEventSink sink = new QueueUiEventSink();
        sink.emit(new UiEvent.AssistantDelta("main", "a"));
        sink.emit(new UiEvent.AssistantDelta("main", "b"));
        sink.emit(new UiEvent.Notice(UiEvent.Severity.INFO, "boundary"));
        sink.emit(new UiEvent.AssistantDelta("main", "c"));

        assertEquals(
                new UiEvent.AssistantDelta("main", "ab"), sink.poll());
        assertEquals(
                new UiEvent.Notice(UiEvent.Severity.INFO, "boundary"),
                sink.poll());
        assertEquals(
                new UiEvent.AssistantDelta("main", "c"), sink.poll());
        assertNull(sink.poll());
    }
}
