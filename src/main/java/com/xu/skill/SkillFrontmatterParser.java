package com.xu.skill;

import java.util.*;

/**
 * SKILL.md Frontmatter 的轻量 YAML 子集解析器。
 *
 * <p>仅支持标量、多行块和行内数组，不支持嵌套对象、逐行数组、锚点和类型标签。
 * 不支持或无效的字段会产生警告并被跳过，不阻止其他 Skill 加载。</p>
 */
public final class SkillFrontmatterParser {

    private SkillFrontmatterParser() {}

    /**
     * 解析 SKILL.md 全文, 返回 frontmatter Map + body 正文 + warning 列表。
     *
     * @param fullText SKILL.md 完整文本
     */
    public static ParseResult parse(String fullText) {
        List<String> warnings = new ArrayList<>();
        String normalized = fullText.replace("\r\n", "\n").replace("\r", "\n");

        // 必须以 --- 开头
        if (!normalized.startsWith("---\n")) {
            return new ParseResult(Map.of(), normalized,
                    List.of("缺少 frontmatter 起始标记 ---"));
        }

        // 找第二个 --- (frontmatter 终止)
        int endIdx = normalized.indexOf("\n---", 4);
        if (endIdx < 0) {
            // 没有终止标记: frontmatter 没关, 但不阻塞, body 返回全文
            warnings.add("未找到 frontmatter 终止标记 ---");
            return new ParseResult(Map.of(), normalized, warnings);
        }

        String fmText = normalized.substring(4, endIdx);         // 去掉开头的 "---\n"
        String body = normalized.substring(endIdx + 4).strip();  // 去掉 "\n---"

        Map<String, Object> frontmatter = parseFrontmatter(fmText, warnings);
        return new ParseResult(frontmatter, body, warnings);
    }

    // ── 解析 frontmatter 文本 ──

    private static Map<String, Object> parseFrontmatter(String text, List<String> warnings) {
        Map<String, Object> map = new LinkedHashMap<>();
        String[] lines = text.split("\n");
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];
            // 空行 / 纯注释 → 跳过
            if (line.isBlank() || line.strip().startsWith("#")) { i++; continue; }

            // 非缩进开头 → 新 key
            int colon = line.indexOf(':');
            if (colon < 0) { i++; continue; }

            String key = line.substring(0, colon).strip();
            String rest = line.substring(colon + 1).strip();

            if (rest.isEmpty()) {
                // "key:" 后面为空 → 可能是多行块 或 空值
                i++;
                if (i < lines.length && isMultilineBlockStart(lines[i])) {
                    // key: |    —— 字面量多行块
                    map.put(key, readMultilineBlock(lines, i, warnings));
                    i = skipBlock(lines, i);
                } else {
                    // 空值
                    map.put(key, "");
                }
                continue;
            }

            // 行内数组: key: [a, b, c]
            if (rest.startsWith("[") && rest.endsWith("]")) {
                map.put(key, parseInlineArray(rest));
                i++;
                continue;
            }

            // 普通单行: key: value
            map.put(key, unquote(rest));
            i++;
        }
        return map;
    }

    // ── 多行块: key: | 开头, 后续行缩进 ≥ 2 空格 ──

    private static boolean isMultilineBlockStart(String line) {
        return line.strip().startsWith("|");
    }

    private static String readMultilineBlock(String[] lines, int startIdx, List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        // 第一行是 "|" 或 "| 内容", 取 | 后面的内容
        String first = lines[startIdx].strip();
        if (first.length() > 1 && first.charAt(0) == '|') {
            String afterPipe = first.substring(1).strip();
            if (!afterPipe.isEmpty()) sb.append(afterPipe);
        }
        // 后续缩进行
        int i = startIdx + 1;
        while (i < lines.length && (lines[i].startsWith("  ") || lines[i].startsWith("\t"))) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i].strip());
            i++;
        }
        return sb.toString();
    }

    private static int skipBlock(String[] lines, int startIdx) {
        int i = startIdx + 1;
        while (i < lines.length && (lines[i].startsWith("  ") || lines[i].startsWith("\t"))) {
            i++;
        }
        return i;
    }

    // ── 行内数组: [a, b, c] ──

    private static List<String> parseInlineArray(String raw) {
        String inner = raw.substring(1, raw.length() - 1).strip();  // 去掉 [ ]
        if (inner.isEmpty()) return List.of();
        return Arrays.stream(inner.split(","))
                .map(String::strip)
                .map(SkillFrontmatterParser::unquote)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ── 去引号 ──

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.strip();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ── 结果 ──

    public record ParseResult(Map<String, Object> frontmatter, String body, List<String> warnings) {}
}
