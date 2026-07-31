package com.xu.ui;

import java.util.function.Consumer;

/**
 * Prevents secrets split across SSE chunks from bypassing redaction.
 *
 * <p>Complete lines are released while streaming; the unfinished line stays
 * buffered until another newline or completion.</p>
 */
public final class StreamingDisplaySanitizer {

    private final Consumer<String> safeOutput;
    private final StringBuilder pending = new StringBuilder();

    public StreamingDisplaySanitizer(Consumer<String> safeOutput) {
        this.safeOutput = safeOutput;
    }

    public synchronized void accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        pending.append(delta);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String completeLine = pending.substring(0, newline + 1);
            pending.delete(0, newline + 1);
            safeOutput.accept(SafeDisplay.redact(completeLine));
        }
    }

    public synchronized void flush() {
        if (pending.isEmpty()) {
            return;
        }
        safeOutput.accept(SafeDisplay.redact(pending.toString()));
        pending.setLength(0);
    }
}
