package com.xu.ui;

/**
 * Destination for user-interface events.
 */
@FunctionalInterface
public interface UiEventSink {

    void emit(UiEvent event);

    /**
     * Only the rich TUI opts into streamed main-agent output. Plain CLI and
     * tests keep the original non-streaming HTTP path for compatibility.
     */
    default boolean supportsStreaming() {
        return false;
    }

    static UiEventSink noop() {
        return event -> {
            // Deliberately empty.
        };
    }
}
