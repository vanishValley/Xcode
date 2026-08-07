package com.xu.memory;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆系统门面，统一协调会话持久化、长期记忆、Token 预算和对话压缩。
 *
 * <p>目标、Plan 上下文和任务级长期记忆只在组装 Prompt 时临时注入，
 * 不写入原始会话历史；同一 ReAct 任务中的长期记忆只检索一次并保持冻结。</p>
 */
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    /** 注入记忆的最大字符数(防注入过多挤占上下文)。 */
    private static final int MAX_CONTEXT_CHARS = 800;

    private final SessionStore sessionStore;
    private final LongTermMemory longTermMemory;
    private final TokenBudget tokenBudget;
    private final ConversationCompactor compactor;

    private final String projectPath;
    private int totalTurns = 0;

    /** 每轮重新注入的目标锚点和 Plan 上下文，防止任务方向随长对话偏移。 */
    private String taskGoal;
    private String taskContext;
    /** 一次 ReAct 任务开始时检索，任务内所有工具轮次复用同一份。 */
    private List<MemoryRecord> frozenMemories = List.of();

    /** 主 Agent 用的完整构造 */
    public MemoryManager(SessionStore sessionStore,
                         LongTermMemory longTermMemory,
                         LlmClient llmClient,
                         String projectPath) {
        this.sessionStore = sessionStore;
        this.longTermMemory = longTermMemory;
        this.tokenBudget = new TokenBudget();
        this.compactor = new ConversationCompactor(llmClient, tokenBudget);
        this.projectPath = projectPath;
    }

    /** 子 Agent 用的构造（无持久化、无压缩） */
    public MemoryManager() {
        this.sessionStore = null;
        this.longTermMemory = null;
        this.tokenBudget = new TokenBudget();
        this.compactor = null;
        this.projectPath = null;
    }

    /** 子 Agent 使用：共享长期记忆，但不持久化和压缩短期会话。 */
    public MemoryManager(
            LongTermMemory longTermMemory,
            String projectPath) {
        this.sessionStore = null;
        this.longTermMemory = longTermMemory;
        this.tokenBudget = new TokenBudget();
        this.compactor = null;
        this.projectPath = projectPath;
    }

    // ────── 任务状态 ──────

    public void setGoal(String goal) { this.taskGoal = goal; }
    public void setContext(String ctx) { this.taskContext = ctx; }
    public boolean hasGoal() { return taskGoal != null && !taskGoal.isEmpty(); }
    /** 清空任务状态。 */
    public void clearTask() {
        this.taskGoal = null;
        this.taskContext = null;
        this.frozenMemories = List.of();
    }

    // ────── Prompt 组装 ──────

    /**
     * 开始一次用户任务。长期记忆只在这里检索一次，后续 ReAct 工具轮次保持不变。
     */
    public void beginTask(String query) {
        if (longTermMemory == null
                || projectPath == null
                || query == null
                || query.isBlank()) {
            frozenMemories = List.of();
            return;
        }
        frozenMemories =
                List.copyOf(longTermMemory.retrieve(query, projectPath));
        if (!frozenMemories.isEmpty()) {
            logger.debug(
                    "记忆检索: 命中 {} 条, query_chars={}",
                    frozenMemories.size(),
                    query.length());
            for (MemoryRecord record : frozenMemories) {
                logger.debug(
                        "  → memory_id={}, source={}, content_chars={}",
                        record.id(),
                        record.source(),
                        record.content() == null
                                ? 0
                                : record.content().length());
            }
        }
    }

    /**
     * 从原始历史和临时上下文组装本轮 Prompt，并返回新的列表。
     *
     * <p>固定顺序：基础 system + Skill 索引 → 目标锚点 → 冻结长期记忆
     * → Plan 上下文 → 原始 user/assistant/tool 历史。
     */
    public List<Message> assemblePrompt(List<Message> cleanHistory) {
        totalTurns++;
        List<Message> prompt = new ArrayList<>();

        // 1. 基础 system prompt + Skill 索引。
        int historyStart = 0;
        if (!cleanHistory.isEmpty()
                && "system".equals(cleanHistory.get(0).role)) {
            prompt.add(cleanHistory.get(0));
            historyStart = 1;
        }

        // 2. 目标锚点。
        if (taskGoal != null && !taskGoal.isEmpty()) {
            prompt.add(new Message("system", "【当前目标】" + taskGoal));
        }

        // 3. 本次任务冻结的长期记忆。
        if (!frozenMemories.isEmpty()) {
            StringBuilder block = new StringBuilder("## 相关记忆\n");
            int chars = 0;
            for (MemoryRecord record : frozenMemories) {
                String line = "- " + record.content() + "\n";
                if (chars + line.length() > MAX_CONTEXT_CHARS) {
                    break;
                }
                block.append(line);
                chars += line.length();
            }
            prompt.add(new Message("system", block.toString()));
        }

        // 4. Plan 上下文。
        if (taskContext != null && !taskContext.isEmpty()) {
            prompt.add(new Message("system", taskContext));
        }

        // 5. 原始 user/assistant/tool 历史；滚动摘要也留在原历史中的原位置。
        for (int i = historyStart; i < cleanHistory.size(); i++) {
            prompt.add(cleanHistory.get(i));
        }

        return prompt;
    }

    /**
     * 检查 Token 预算，必要时就地压缩历史。
     * @return 压缩后的消息列表（可能和输入是同一个引用）
     */
    public List<Message> compactIfNeeded(List<Message> history) {
        if (compactor == null) return history;
        if (!tokenBudget.shouldCompress(history)) return history;
        return compactor.compact(history, totalTurns);
    }

    /** 保存会话到磁盘 */
    public void persist(List<Message> history) {
        if (sessionStore == null || projectPath == null) return;
        try {
            sessionStore.save(projectPath, history);
        } catch (IOException e) {
            logger.error("保存会话失败: {}", e.getMessage());
        }
    }

    // ────── 长期记忆管理 ──────

    /** 手动保存项目级人工记忆。 */
    public LongTermMemory.SaveResult saveFact(String content) {
        return saveFact(content, MemoryScope.PROJECT);
    }

    /** 按指定作用域保存人工记忆；未配置长期记忆的子 Agent 返回 {@code null}。 */
    public LongTermMemory.SaveResult saveFact(
            String content,
            MemoryScope scope) {
        if (longTermMemory == null) return null;
        return longTermMemory.save(MemoryRecord.create(
                content,
                scope,
                projectPath,
                MemorySource.HUMAN,
                0.9));
    }

    public List<MemoryRecord> listFacts() {
        return longTermMemory != null
                ? longTermMemory.list(projectPath)
                : List.of();
    }

    /** 返回低置信度、等待人工确认的 Agent 记忆候选。 */
    public List<MemoryRecord> pendingReview() {
        return longTermMemory != null
                ? longTermMemory.pendingReview()
                : List.of();
    }

    public void clearFacts() {
        if (longTermMemory != null) longTermMemory.clear();
    }

    // ────── 自动沉淀 ──────

    /** 尽力从“失败→修正→成功”的执行记录中提炼经验；失败不影响主流程。 */
    public void tryAutoExtract(List<Message> history, LlmClient llmClient) {
        if (longTermMemory == null || projectPath == null) return;
        LessonExtractor.tryExtract(
                history,
                llmClient,
                longTermMemory,
                projectPath);
    }

    // ────── 会话管理 ──────

    public List<Message> loadSession() {
        if (sessionStore == null) return List.of();
        try {
            return sessionStore.load(projectPath);
        } catch (IOException e) {
            return List.of();
        }
    }

    public void deleteSession() {
        if (sessionStore == null) return;
        try {
            sessionStore.delete(projectPath);
        } catch (IOException e) {
            logger.error("删除会话失败: {}", e.getMessage());
        }
    }

    public void resetCompactor() {
        if (compactor != null) compactor.reset();
        totalTurns = 0;
    }

    // ────── 观测 ──────

    public double contextUsagePercent(List<Message> history) {
        return tokenBudget.usagePercent(history);
    }
}
