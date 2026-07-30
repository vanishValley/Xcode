package com.xu.skill;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一个 Skill = 一个 Markdown 文件(SKILL.md)解析后的完整数据。
 *
 * name 不能为空(缺失时回退到目录名); body 是 --- 之后的所有内容;
 * source 标记来源(BUILTIN / USER / PROJECT), 用于调试和 /skill list 展示。
 */
public record Skill(
        String name,
        String description,
        String version,
        String author,
        List<String> tags,
        Source source,
        String body,
        Path skillMdPath,
        Path referencesDir
) {
    public enum Source { BUILTIN, USER, PROJECT }

    public Skill {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        description = Objects.requireNonNullElse(description, "");
        version = Objects.requireNonNullElse(version, "");
        author = Objects.requireNonNullElse(author, "");
        tags = tags == null ? List.of() : Collections.unmodifiableList(tags);
        body = Objects.requireNonNullElse(body, "");
        source = Objects.requireNonNull(source);
    }
}
