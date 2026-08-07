package com.xu.ui;

import java.util.function.Consumer;

/**
 * 防止被拆分到多个 SSE 片段中的凭据绕过脱敏。
 *
 * <p>流式阶段只释放完整行；未结束的行保留到收到换行或流结束后再处理。</p>
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
