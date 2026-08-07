package com.xu.ui.tui;

import com.xu.ui.SafeDisplay;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/** 轻量终端 Markdown 渲染器，只支持有助于阅读的语法，并且从不解释 ANSI。 */
final class MarkdownRenderer {

    List<AttributedString> render(String markdown) {
        return render(markdown, false).lines();
    }

    RenderResult render(String markdown, boolean initialCodeBlock) {
        String safe = SafeDisplay.redact(markdown == null ? "" : markdown);
        String[] lines = safe.split("\\n", -1);
        List<AttributedString> rendered = new ArrayList<>(lines.length);
        boolean codeBlock = initialCodeBlock;

        for (String line : lines) {
            if (line.stripLeading().startsWith("```")) {
                codeBlock = !codeBlock;
                String language =
                        line.stripLeading().substring(3).strip();
                if (!language.isEmpty()) {
                    rendered.add(new AttributedStringBuilder()
                            .append("  ")
                            .styled(TuiTheme.SECONDARY, language)
                            .toAttributedString());
                }
                continue;
            }
            if (codeBlock) {
                rendered.add(new AttributedStringBuilder()
                        .styled(TuiTheme.SECONDARY, "│ ")
                        .styled(TuiTheme.CODE, line)
                        .toAttributedString());
                continue;
            }

            String stripped = line.stripLeading();
            if (stripped.startsWith("### ")) {
                rendered.add(styledInline(
                        "  " + stripped.substring(4),
                        TuiTheme.HEADING));
            } else if (stripped.startsWith("## ")) {
                rendered.add(styledInline(
                        "  " + stripped.substring(3),
                        TuiTheme.HEADING));
            } else if (stripped.startsWith("# ")) {
                rendered.add(styledInline(
                        stripped.substring(2),
                        TuiTheme.BRAND));
            } else if (stripped.matches("^[-*] .*")) {
                rendered.add(styledInline(
                        "  • " + stripped.substring(2),
                        AttributedStyle.DEFAULT));
            } else if (stripped.matches("^\\d+\\. .*")) {
                rendered.add(styledInline(
                        "  " + stripped, AttributedStyle.DEFAULT));
            } else if (stripped.startsWith("> ")) {
                rendered.add(new AttributedStringBuilder()
                        .styled(TuiTheme.SECONDARY, "  │ ")
                        .append(styledInline(
                                stripped.substring(2),
                                AttributedStyle.DEFAULT))
                        .toAttributedString());
            } else {
                rendered.add(styledInline(
                        line, AttributedStyle.DEFAULT));
            }
        }
        return new RenderResult(rendered, codeBlock);
    }

    private AttributedString styledInline(
            String text,
            AttributedStyle base) {
        AttributedStringBuilder output = new AttributedStringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int codeStart = text.indexOf('`', cursor);
            int boldStart = text.indexOf("**", cursor);
            int next = earliest(codeStart, boldStart);
            if (next < 0) {
                output.styled(base, text.substring(cursor));
                break;
            }
            output.styled(base, text.substring(cursor, next));
            if (next == codeStart) {
                int end = text.indexOf('`', next + 1);
                if (end < 0) {
                    output.styled(base, text.substring(next));
                    break;
                }
                output.styled(
                        TuiTheme.CODE,
                        text.substring(next + 1, end));
                cursor = end + 1;
            } else {
                int end = text.indexOf("**", next + 2);
                if (end < 0) {
                    output.styled(base, text.substring(next));
                    break;
                }
                output.styled(
                        new AttributedStyle(base).bold(),
                        text.substring(next + 2, end));
                cursor = end + 2;
            }
        }
        if (text.isEmpty()) {
            output.append("");
        }
        return output.toAttributedString();
    }

    private static int earliest(int first, int second) {
        if (first < 0) return second;
        if (second < 0) return first;
        return Math.min(first, second);
    }

    record RenderResult(
            List<AttributedString> lines,
            boolean codeBlock) {
    }
}
