package com.xu.tool.impl;

import com.xu.tool.Tool;
import com.xu.util.FileUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 在项目根目录内创建或覆盖文件。路径解析后必须仍位于项目根下，单次写入不超过 5 MB；
 * 合法的绝对路径可以使用，但不能借此越过工作区边界。
 */
public class WriteFileTool implements Tool {

    /** 单次写入上限：5MB */
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    /** 项目根目录，所有相对路径都基于它解析 */
    private final Path projectRoot;

    /** 默认构造：项目根 = 当前工作目录 */
    public WriteFileTool() {
        this(Path.of(".").toAbsolutePath().normalize());
    }

    /** 指定项目根目录（测试用） */
    public WriteFileTool(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "创建或覆盖写入文件。参数：path（文件路径，相对于项目根目录，会自动创建父目录）、" +
                "content（写入内容）。单次最大 5MB。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "要写入的文件路径，相对于项目根目录"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "要写入文件的内容"
                        )
                ),
                "required", java.util.List.of("path", "content")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        // ===== 1. 参数校验 =====
        String pathStr = (String) arguments.get("path");
        String content = (String) arguments.get("content");

        if (pathStr == null || pathStr.isBlank()) {
            return "错误：缺少 path 参数";
        }
        if (content == null) {
            return "错误：缺少 content 参数";
        }

        // ===== 2. 路径安全校验 =====
        // 核心思想：所有路径相对于 projectRoot 解析，然后检查解析后是否还在根内。
        // 这样无论 LLM 传相对路径还是绝对路径，只要最终落点在根内就允许。
        Path filePath = projectRoot.resolve(pathStr).normalize();

        // toRealPath 会解析符号链接，防止通过 /tmp/link -> /etc 这类方式逃逸
        Path realRoot = projectRoot.toRealPath();
        Path realFile = filePath;
        try {
            // 父目录不存在时无法 toRealPath，先尝试创建
            if (Files.exists(filePath)) {
                realFile = filePath.toRealPath();
            } else if (filePath.getParent() != null && Files.exists(filePath.getParent())) {
                realFile = filePath.getParent().toRealPath().resolve(filePath.getFileName());
            }
            // 如果父目录都不存在，跳过 realPath 检查，后续写入时会自动创建
        } catch (java.io.IOException ignored) {
            // 解析失败就信任 normalize 后的路径
        }

        if (!realFile.startsWith(realRoot)) {
            return String.format(
                    "错误：路径逃逸到项目根之外（%s 不在 %s 内），已拦截",
                    realFile, realRoot);
        }

        // ===== 3. 内容大小校验 =====
        // 用 UTF-8 字节数判断，比 String.length() 更准确（中文字符占 3 字节）
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return String.format(
                    "错误：内容过大（%.1f MB），超过 %.1f MB 上限，请拆分写入",
                    bytes.length / (1024.0 * 1024.0), MAX_BYTES / (1024.0 * 1024.0));
        }

        // ===== 4. 执行写入 =====
        try {
            // 原子写入：先写同目录 .tmp 再 rename 覆盖。写到一半崩溃也不会留下半成品文件，
            // 其他进程/读者永远读不到"写了一半"的中间态。父目录不存在时 FileUtils 会自动创建。
            FileUtils.atomicWrite(filePath, bytes);
            return String.format("写入成功：%s（%d 字节）", pathStr, bytes.length);
        } catch (java.nio.file.AccessDeniedException e) {
            return "错误：没有权限写入 " + pathStr;
        } catch (Exception e) {
            return "写入失败：" + e.getMessage();
        }
    }
}
