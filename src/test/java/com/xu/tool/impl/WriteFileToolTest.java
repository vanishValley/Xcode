package com.xu.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WriteFileTool 测试
 *
 * 此处使用 projectRoot = @TempDir，所以用相对路径就能在临时目录里测试。
 * 真实运行时会传 new WriteFileTool()，projectRoot = 当前工作目录。
 */
class WriteFileToolTest {

    @TempDir
    Path tempDir;

    // ===== 正常路径 =====

    @Test
    void shouldWriteNewFile() throws Exception {
        WriteFileTool tool = new WriteFileTool(tempDir);

        String result = tool.execute(Map.of(
                "path", "新建.txt",          // 相对路径，解析到 tempDir/新建.txt
                "content", "测试内容"
        ));

        assertTrue(result.contains("写入成功"), "实际结果: " + result);
        assertEquals("测试内容", Files.readString(tempDir.resolve("新建.txt")));
    }

    @Test
    void shouldCreateParentDirs() throws Exception {
        WriteFileTool tool = new WriteFileTool(tempDir);

        tool.execute(Map.of(
                "path", "深层/目录/目标.txt",
                "content", "嵌套文件"
        ));

        Path expected = tempDir.resolve("深层/目录/目标.txt");
        assertTrue(Files.exists(expected));
        assertEquals("嵌套文件", Files.readString(expected));
    }

    // ===== 安全边界 =====

    @Test
    void shouldRejectPathOutsideProjectRoot() throws Exception {
        WriteFileTool tool = new WriteFileTool(tempDir);

        // 绝对路径指向系统目录，一定逃逸出 projectRoot
        String absPath = System.getProperty("os.name").toLowerCase().contains("win")
                ? "C:\\Windows\\System32\\test.txt"
                : "/etc/passwd";

        String result = tool.execute(Map.of(
                "path", absPath,
                "content", "不该写入的内容"
        ));

        assertTrue(result.contains("逃逸") || result.contains("拦截"),
                "期望拦截绝对路径逃逸，实际: " + result);
    }

    @Test
    void shouldRejectPathTraversal() throws Exception {
        WriteFileTool tool = new WriteFileTool(tempDir);

        // "../escape.txt" resolve 到 tempDir 的父目录 → 逃逸
        String result = tool.execute(Map.of(
                "path", "../escape.txt",
                "content", "穿越内容"
        ));

        assertTrue(result.contains("逃逸") || result.contains("拦截"),
                "期望拦截 .. 穿越，实际: " + result);
    }

    @Test
    void shouldRejectOversizedContent() throws Exception {
        WriteFileTool tool = new WriteFileTool(tempDir);

        String big = "A".repeat(6 * 1024 * 1024); // ~6MB
        String result = tool.execute(Map.of(
                "path", "big.txt",
                "content", big
        ));

        assertTrue(result.contains("过大"),
                "期望拒绝超大内容，实际: " + result);
    }

    // ===== 参数校验 =====

    @Test
    void shouldReportMissingContent() throws Exception {
        WriteFileTool tool = new WriteFileTool(tempDir);
        String result = tool.execute(Map.of("path", "test.txt"));
        assertTrue(result.contains("缺少 content 参数"));
    }
}
