package com.xu.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadFileTool 测试
 *
 * 覆盖三种情况：
 *   1. 正常读已存在文件
 *   2. 文件不存在 → 友好报错
 *   3. 缺少参数 → 友好报错
 */
class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();

    @TempDir
    Path tempDir;   // JUnit 5 自动创建临时目录，测试结束自动删除

    @Test
    void shouldReadExistingFile() throws Exception {
        // 准备：在临时目录创建一个文件
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "Hello, World!");

        // 执行
        String result = tool.execute(Map.of("path", file.toString()));

        // 验证
        assertTrue(result.contains("Hello, World!"));
    }

    @Test
    void shouldReportFileNotFound() throws Exception {
        String result = tool.execute(Map.of("path", "不存在的文件.txt"));
        assertTrue(result.contains("文件不存在"));
    }

    @Test
    void shouldReportMissingPathParam() throws Exception {
        String result = tool.execute(Map.of());
        assertTrue(result.contains("缺少 path 参数"));
    }
}
