package com.xu.ui.tui;

import com.xu.ui.SafeDisplay;
import org.jline.reader.impl.history.DefaultHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * JLine history that refuses to retain values recognized as credentials.
 *
 * <p>This boundary is intentionally stricter than display redaction: replacing
 * a credential would create a misleading command, so the entire entry is
 * omitted. Existing sensitive lines are filtered while loading and the file is
 * rewritten immediately.</p>
 */
final class SafeHistory extends DefaultHistory {

    private final Path historyFile;
    private boolean filteredDuringLoad;

    SafeHistory(Path historyFile) {
        this.historyFile = historyFile;
    }

    @Override
    public void add(Instant time, String line) {
        if (isSafe(line)) {
            super.add(time, line);
        }
    }

    @Override
    protected void addHistoryLine(Path path, String line) {
        if (isSafe(line)) {
            super.addHistoryLine(path, line);
        } else {
            filteredDuringLoad = true;
        }
    }

    @Override
    protected void addHistoryLine(
            Path path,
            String line,
            boolean incremental) {
        if (isSafe(line)) {
            super.addHistoryLine(path, line, incremental);
        } else {
            filteredDuringLoad = true;
        }
    }

    @Override
    public void load() throws IOException {
        filteredDuringLoad = false;
        super.load();
        if (filteredDuringLoad && historyFile != null) {
            super.write(historyFile, false);
        }
    }

    static boolean isSafe(String line) {
        return line != null
                && SafeDisplay.redact(line).equals(line);
    }
}
