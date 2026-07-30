package com.xu.tool.impl;

import com.xu.tool.Tool;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件名 glob 搜索工具 —— Agent 探索代码库的核心能力。
 *
 * 对标 PaiCLI / Claude Code 的 glob_files：
 *   - 支持 ** 递归匹配（如 "**\/*.java" 找所有 Java 文件）
 *   - 自动跳过常见构建/IDE/依赖目录（target、.git、node_modules 等）
 *   - 结果去重、排序、限制上限
 *
 * 为什么需要这个工具？
 *   list_dir 只能看一层，Agent 不知道深层有什么文件。
 *   glob_files 一句 "**\/*Test.java" 就能搜遍全项目，
 *   比让 LLM 反复一层层 list_dir 高效得多。
 */
public class GlobFilesTool implements Tool {

    private static final int MAX_RESULTS = 500;

    /** 自动跳过的目录名 */
    private static final Set<String> SKIP_DIRS = Set.of(
            "target", ".git", ".idea",  "node_modules",
            "dist", "build", "__pycache__", ".gradle", ".svn"
    );

    private final Path projectRoot;

    public GlobFilesTool() {
        this(Path.of(".").toAbsolutePath().normalize());
    }

    public GlobFilesTool(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "glob_files";
    }

    @Override
    public String description() {
        return "按文件名模式搜索项目中的文件，支持 ** 递归匹配。例如：**/*.java 找所有 Java 文件、" +
                "src/**/*Test*.java 限定在 src 下找测试文件。" +
                "结果最多返回 " + MAX_RESULTS + " 条。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of(
                                "type", "string",
                                "description", "文件匹配模式，如 **/*.java、src/**/*.xml。" +
                                        "* 匹配单层任意字符，** 匹配任意层级目录"
                        )
                ),
                "required", List.of("pattern")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        // ===== 1. 参数校验 =====
        String pattern = (String) arguments.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "错误：缺少 pattern 参数，例如 **/*.java";
        }

        // ===== 2. 构建 PathMatcher =====
        // Java 的 FileSystem.getPathMatcher 用 "glob:..." 语法
        // 注意：glob 语法要求 ** 匹配跨目录，*.java 匹配单层
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (Exception e) {
            return "错误：无效的匹配模式 '" + pattern + "'：" + e.getMessage();
        }

        // ===== 3. 遍历文件树 =====
        // SimpleFileVisitor 比递归 list 更干净，能在一个地方处理"跳过"逻辑
        List<String> results = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 跳过不需要的目录（列目录名，大小写不敏感）
                String name = dir.getFileName().toString().toLowerCase();
                if (SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // 跳过隐藏目录（.开头，但 . 本身除外）
                if (name.startsWith(".") && !name.equals(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (results.size() >= MAX_RESULTS) {
                    return FileVisitResult.TERMINATE;
                }
                // 相对化路径（去掉 projectRoot 前缀），让输出更可读
                Path relative = projectRoot.relativize(file);
                if (matcher.matches(relative)) {
                    results.add(relative.toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                // 权限不足或无响应的文件直接跳过，不中断整个搜索
                return FileVisitResult.SKIP_SUBTREE;
            }
        });

        // ===== 4. 格式化输出 =====
        if (results.isEmpty()) {
            return "未找到匹配 '" + pattern + "' 的文件";
        }

        // 自然排序（忽略大小写）
        results.sort(String.CASE_INSENSITIVE_ORDER);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("匹配 '%s' 的文件（共 %d 个）：\n", pattern, results.size()));
        for (String r : results) {
            sb.append("  ").append(r).append("\n");
        }
        if (results.size() >= MAX_RESULTS) {
            sb.append("  ... [结果已达上限 " + MAX_RESULTS + "，请缩小搜索范围]");
        }
        return sb.toString();
    }
}
