package com.xu.ui.tui;

import com.xu.ui.SafeDisplay;
import org.jline.reader.impl.history.DefaultHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 拒绝保存疑似凭据的 JLine 历史。
 *
 * <p>历史边界比显示脱敏更严格：替换凭据会得到含义错误的命令，因此直接丢弃整条记录。
 * 加载时也会过滤旧的敏感行，并立即重写历史文件。</p>
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
