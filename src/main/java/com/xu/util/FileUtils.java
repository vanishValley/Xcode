package com.xu.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件工具类 —— 收敛项目里重复的原子写逻辑。
 *
 * 为什么要有这个类？
 *   原子写（tmp + rename）原本只在 SessionStore 里。随着 PlanStore（checkpoint 落盘）、
 *   WriteFileTool（产物写）都要用它，三份复制就是坏味道（Duplicated Code）。
 *   抽到这里一处实现、三处复用，以后要改（比如加 fsync 强制落盘）只改一个地方。
 *
 * 设计约定：
 *   - 纯静态无状态工具类，私有构造防实例化
 *   - 只负责"怎么原子地写"，不负责"写失败怎么办" —— 异常直接往外抛，
 *     让调用方各自决定降级策略（会话落盘打日志、checkpoint best-effort、
 *     产物写把错误返回给 LLM）。公共的是"怎么写"，不公共的是"失败怎么办"。
 */
public final class FileUtils {

    private FileUtils() {}   // 工具类不允许实例化

    /**
     * 原子写入字节内容。
     *
     * 做法：先写同目录下的 .tmp 临时文件，再用文件系统级的 rename 覆盖目标。
     * rename 是原子操作 —— 要么旧文件完好、要么新文件完整，绝不出现"写一半"的
     * 中间态，其他读者也永远读不到半成品文件。
     *
     * 注意：tmp 必须和 target 在同一目录（用 resolveSibling），因为 ATOMIC_MOVE
     *   要求源和目标在同一文件系统，否则会抛异常。
     *
     * @param target  目标文件
     * @param content 要写入的字节
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

    /**
     * 原子写入文本内容（UTF-8）。内部委托给字节版本，避免重复实现。
     */
    public static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
