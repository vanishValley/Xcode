package com.xu.ui.tui;

import org.jline.utils.AttributedStyle;

/** Theme-safe 16-color palette; status never relies on color alone. */
final class TuiTheme {

    static final AttributedStyle BRAND = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN).bold();
    static final AttributedStyle PROMPT = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN).bold();
    static final AttributedStyle SUCCESS = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.GREEN);
    static final AttributedStyle WARNING = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW);
    static final AttributedStyle ERROR = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.RED);
    static final AttributedStyle ACCENT = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BLUE);
    static final AttributedStyle SECONDARY = AttributedStyle.DEFAULT.faint();
    static final AttributedStyle CODE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN);
    static final AttributedStyle HEADING = AttributedStyle.DEFAULT.bold();

    private TuiTheme() {
    }
}
