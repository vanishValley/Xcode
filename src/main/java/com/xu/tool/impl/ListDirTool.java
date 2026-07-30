package com.xu.tool.impl;

import com.xu.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 目录列表工具 —— Agent 靠它了解项目结构。
 *
 * 设计要点：
 *   - 默认只列一级（不递归），避免输出爆炸。LLM 想看子目录再单独发一次
 *   - 输出文件名 + 类型标记（[目录] / [文件]），LLM 容易理解
 *   - 按名称排序，结果稳定
 */
public class ListDirTool implements Tool {

    /** 单次列出的最大条目数，防止项目太大输出爆炸 */
    private static final int MAX_ENTRIES = 200;

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "列出指定目录的内容（默认只列一级，不递归）。参数：path（可选，默认当前项目根目录）。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "要列出内容的目录路径，相对于项目根目录。不填则列出项目根目录"
                        )
                ),
                "required", java.util.List.of()  // path 可选
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        // ===== 1. 确定目标路径 =====
        String pathStr = (String) arguments.getOrDefault("path", ".");
        Path dirPath = Path.of(pathStr);

        if (!Files.exists(dirPath)) {
            return "目录不存在：" + pathStr;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + pathStr + "，请用 read_file 读取文件";
        }

        // ===== 2. 列出并格式化 =====
        // try-with-resources 保证 Stream 用完关闭（底层持有 OS 文件句柄）
        try (Stream<Path> entries = Files.list(dirPath)) {
            var items = entries
                    .sorted((a, b) -> {
                        // 目录排前面，同类按名称排序
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir && !bDir) return -1;
                        if (!aDir && bDir) return 1;
                        return a.getFileName().toString()
                                .compareToIgnoreCase(b.getFileName().toString());
                    })
                    .limit(MAX_ENTRIES)
                    .map(p -> {
                        String name = p.getFileName().toString();
                        // 对常见的"该忽略"目录做标记，帮助 LLM 理解
                        if (Files.isDirectory(p)) {
                            return name + "/";
                        } else {
                            // 显示文件大小，方便 LLM 判断是不是该读
                            try {
                                long size = Files.size(p);
                                return name + "  (" + formatSize(size) + ")";
                            } catch (Exception e) {
                                return name;
                            }
                        }
                    })
                    .collect(Collectors.toList());

            if (items.isEmpty()) {
                return pathStr + " 是空目录";
            }

            int count = items.size();
            String header = "目录 " + pathStr + " 的内容（共 " + count + " 项）：\n";
            return header + String.join("\n  ", items);
        }
    }

    /** 人类友好的文件大小显示 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
