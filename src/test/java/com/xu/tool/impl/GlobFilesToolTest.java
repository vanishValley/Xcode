package com.xu.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobFilesToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldFindJavaFiles() throws Exception {
        // 准备：建几个 .java 和 .txt
        Files.createDirectories(tempDir.resolve("src/main/java/com/xu"));
        Files.createDirectories(tempDir.resolve("src/test/java/com/xu"));
        Files.writeString(tempDir.resolve("src/main/java/com/xu/Main.java"), "// Main");
        Files.writeString(tempDir.resolve("src/main/java/com/xu/Util.java"), "// Util");
        Files.writeString(tempDir.resolve("src/test/java/com/xu/MainTest.java"), "// Test");
        Files.writeString(tempDir.resolve("README.md"), "# README");

        GlobFilesTool tool = new GlobFilesTool(tempDir);
        String result = tool.execute(Map.of("pattern", "**/*.java"));

        assertTrue(result.contains("Main.java"));
        assertTrue(result.contains("Util.java"));
        assertTrue(result.contains("MainTest.java"));
        assertFalse(result.contains("README.md"), "不该包含 .md 文件");
    }

    @Test
    void shouldRestrictToSubdirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/App.java"), "");
        Files.writeString(tempDir.resolve("src/main/Helper.java"), "");
        // 这个文件在根目录，不应出现在 src/** 的结果里
        Files.createDirectories(tempDir.resolve("other"));
        Files.writeString(tempDir.resolve("other/Other.java"), "");

        GlobFilesTool tool = new GlobFilesTool(tempDir);
        String result = tool.execute(Map.of("pattern", "src/**/*.java"));

        assertTrue(result.contains("App.java"));
        assertTrue(result.contains("Helper.java"));
        assertFalse(result.contains("Other.java"), "不该包含 src 目录之外的文件");
    }

    @Test
    void shouldSkipTargetDir() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("src/Real.java"), "");
        Files.writeString(tempDir.resolve("target/Generated.java"), "");

        GlobFilesTool tool = new GlobFilesTool(tempDir);
        String result = tool.execute(Map.of("pattern", "**/*.java"));

        assertTrue(result.contains("Real.java"));
        assertFalse(result.contains("Generated.java"), "应该跳过 target 目录");
    }

    @Test
    void shouldReportNoMatch() throws Exception {
        GlobFilesTool tool = new GlobFilesTool(tempDir);
        String result = tool.execute(Map.of("pattern", "**/*.xyz"));

        assertTrue(result.contains("未找到匹配") || result.contains("未找到"));
    }

    @Test
    void shouldReportMissingPattern() throws Exception {
        GlobFilesTool tool = new GlobFilesTool(tempDir);
        String result = tool.execute(Map.of());

        assertTrue(result.contains("缺少 pattern"));
    }
}
