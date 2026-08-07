package com.xu.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 统一提供原子文件写入。此类只保证写入过程的一致性；失败后的日志、降级或错误回传
 * 由 SessionStore、PlanStore、WriteFileTool 等调用方分别处理。
 */
public final class FileUtils {

    private FileUtils() {}

    /**
     * 先写入目标文件同目录的临时文件，再通过 {@code ATOMIC_MOVE} 原子替换目标，
     * 避免其他读者看到只写入一部分的内容。
     *
     * @param target 目标文件
     * @param content 待写入字节
     */
    public static void atomicWrite(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** 使用 UTF-8 原子写入文本。 */
    public static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
