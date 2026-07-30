package com.xu.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ListDirTool 测试
 *
 * 覆盖：正常列出 / 空目录 / 不存在的路径 / 路径是文件而非目录
 */
class ListDirToolTest {

    private final ListDirTool tool = new ListDirTool();

    @TempDir
    Path tempDir;

    @Test
    void shouldListDirectoryContents() throws Exception {
        // 创建几个文件和子目录
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("pom.xml"), "test");
        Files.writeString(tempDir.resolve("README.md"), "readme content");

        String result = tool.execute(Map.of("path", tempDir.toString()));

        // 目录在前
        assertTrue(result.contains("src/"));
        // 文件在后，带大小
        assertTrue(result.contains("pom.xml"));
        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldReportEmptyDir() throws Exception {
        Path emptyDir = tempDir.resolve("空目录");
        Files.createDirectory(emptyDir);

        String result = tool.execute(Map.of("path", emptyDir.toString()));

        assertTrue(result.contains("空目录"));
    }

    @Test
    void shouldReportNonExistentDir() throws Exception {
        String result = tool.execute(Map.of("path", "/不存在的目录"));

        assertTrue(result.contains("目录不存在"));
    }

    @Test
    void shouldDefaultToCurrentDirWhenNoPath() throws Exception {
        // 不传 path，列出当前工作目录（项目根）
        String result = tool.execute(Map.of());

        // 应该能看到 pom.xml
        assertTrue(result.contains("pom.xml"));
    }
}
