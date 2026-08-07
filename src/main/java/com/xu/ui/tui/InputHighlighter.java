package com.xu.ui.tui;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/** 只高亮可信的用户输入语法，不解释 ANSI 控制序列。 */
final class InputHighlighter implements Highlighter {

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        AttributedStringBuilder output = new AttributedStringBuilder();
        if (buffer.startsWith("/")) {
            int space = buffer.indexOf(' ');
            if (space < 0) {
                output.styled(TuiTheme.BRAND, buffer);
            } else {
                output.styled(TuiTheme.BRAND, buffer.substring(0, space))
                        .append(buffer.substring(space));
            }
        } else {
            output.append(buffer);
        }
        return output.toAttributedString();
    }
}
