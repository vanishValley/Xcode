package com.xu.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.llm.LlmClient.Message;
import com.xu.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话持久化 —— 重启后恢复对话上下文。
 *
 * 设计要点：
 *   1. 每个项目一个会话文件，通过项目路径 hash 隔离
 *   2. JSONL 格式：每行一条 Message JSON，和 OpenAI 协议一致，人类可读
 *   3. 原子写入：先写 .tmp，再 rename，防止进程崩溃损坏文件
 *   4. 200 条上限：超出时截断保留最近 200 条（真正的语义压缩在 ConversationCompactor）
 *
 * 为什么用文件而不是数据库？
 *   对标 Claude Code / PaiCLI 的设计哲学——文件人类可读、
 *   可以直接编辑、不依赖额外驱动。
 */
public class SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionStore.class);

    private final Path sessionsDir;

    /** 单文件最多保留的消息数 */
    private static final int MAX_MESSAGES = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 默认构造（测试用） */
    public SessionStore() {
        this(Path.of(System.getProperty("user.home"), ".xcode", "projects"));
    }

    /** 指定会话目录（测试用） */
    public SessionStore(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    /** 主构造：从 XcodePaths 拿项目数据路径，hash/标准化等全部归 XcodePaths 管 */
    public SessionStore(com.xu.config.XcodePaths paths) {
        this(paths.projectDataDir());
    }

    /**
     * 从磁盘恢复会话历史。
     *
     * @param projectPath 项目根路径（用于区分不同项目）
     * @return 历史消息列表（空 = 没有保存的会话）
     */
    public List<Message> load(String projectPath) throws IOException {
        Path file = sessionFile(projectPath);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        List<Message> messages = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                Message msg = objectMapper.readValue(line, Message.class);
                messages.add(msg);
            } catch (Exception e) {
                // 单行损坏不阻塞整体恢复——记录日志后跳过
                logger.warn("跳过损坏的行: {}", line.substring(0, Math.min(80, line.length())));
            }
        }
        return messages;
    }

    /**
     * 持久化当前会话历史（全量原子重写整个文件，非追加）。
     *
     * @param projectPath 项目根路径
     * @param allMessages 完整的历史（包含新增的消息）
     */
    public void save(String projectPath, List<Message> allMessages) throws IOException {
        Path file = sessionFile(projectPath);

        // 超出上限时，截掉最旧的（保留最近 MAX_MESSAGES 条）
        List<Message> toSave = allMessages;
        if (allMessages.size() > MAX_MESSAGES) {
            toSave = allMessages.subList(
                    allMessages.size() - MAX_MESSAGES, allMessages.size());
            logger.atWarn()
                    .addKeyValue("event", "session.messages.truncated")
                    .addKeyValue("messages_before", allMessages.size())
                    .addKeyValue("messages_retained", toSave.size())
                    .log("会话消息超过持久化上限");
        }

        // 序列化为 JSONL
        StringBuilder sb = new StringBuilder();
        for (Message msg : toSave) {
            sb.append(objectMapper.writeValueAsString(msg)).append("\n");
        }

        FileUtils.atomicWrite(file, sb.toString());
    }

    /**
     * 删除项目的会话文件（/clear 时调用）。
     */
    public void delete(String projectPath) throws IOException {
        Path file = sessionFile(projectPath);
        Files.deleteIfExists(file);
    }

    /**
     * 返回会话文件大小（用于调试/观测）。
     */
    public long fileSize(String projectPath) throws IOException {
        Path file = sessionFile(projectPath);
        return Files.exists(file) ? Files.size(file) : 0;
    }

    /**
     * 返回项目数据目录（session.jsonl 和 long_term_memory.json 都在这里）。
     */
    public Path projectDir(String projectPath) {
        return sessionFile(projectPath).getParent();
    }

    /**
     * 根据项目路径计算会话文件路径。
     * 结构：sessionsDir / <项目名@hash> / session.jsonl
     */
    private Path sessionFile(String projectPath) {
        String hash = Integer.toHexString(projectPath.hashCode());
        String projectName = Path.of(projectPath).getFileName().toString();
        String dirName = projectName + "@" + hash;
        return sessionsDir.resolve(dirName).resolve("session.jsonl");
    }
}
