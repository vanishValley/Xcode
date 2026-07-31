package com.xu.ui.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarkdownRendererTest {

    @Test
    void shouldRenderReadableTextWithoutTrustingAnsi() {
        var lines = new MarkdownRenderer().render(
                "# Title\n- **done** and `code`\n```java\n"
                        + "\u001B[31mclass Demo {}\u001B[0m\n```");

        String text = lines.stream()
                .map(Object::toString)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        assertEquals(
                "Title\n  • done and code\n  java\n│ class Demo {}",
                text);
        assertFalse(text.contains("\u001B"));
    }
}
