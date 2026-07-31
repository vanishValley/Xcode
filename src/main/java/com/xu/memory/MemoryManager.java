package com.xu.memory;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory 系统门面 —— Agent 通过这一个类操作整个记忆系统。
 *
 * 内部组件:
 *   SessionStore          — 会话持久化(对话历史)
 *   LongTermMemory        — 长期知识(存储 + 检索 + 写入治理)
 *   TokenBudget           — Token 估算
 *   ConversationCompactor — 对话压缩
 *
 * Agent 每次任务调用:
 *   beginTask(query)         → 冻结本次 ReAct 任务的长期记忆
 *
 * Agent 每个工具轮次调用:
 *   compactIfNeeded(history) → 检查并原地压缩历史
 *   assemblePrompt(history)  → 组装本轮 prompt,返回新 List
 *
 * Agent 任务结束调用:
 *   persist(history)         → 保存干净历史到磁盘
 *
 * 注入块不进干净历史 → session 落盘不被污染 → 不再需要 removeIf 擦除。
 * 任务状态(setGoal/setContext)存在 MemoryManager,每轮 assemblePrompt 自动重贴。
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

    /** 任务状态:目标锚点(每轮重贴防偏移) + 外部上下文(Main 回灌的 plan 报告) */
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

    /** 子 Agent 用的构造:共享长期知识检索 + 目标锚点,无会话落盘/压缩(子任务历史短) */
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

    // ────── 每轮统一组装(收敛散落注入,不污染干净历史) ──────

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
     * 从干净历史 + 注入块组装本轮 prompt,返回新 List。
     * 注入块不进干净历史 → session 落盘不被污染 → 不再需要 removeIf 擦除。
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
     * 检查是否需要压缩,需要则就地压缩 history。
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

    // ────── 长期知识 CRUD（Main 的命令直接调） ──────

    /** 手动 /save:默认 PROJECT 作用域、HUMAN 来源。 */
    public LongTermMemory.SaveResult saveFact(String content) {
        return saveFact(content, MemoryScope.PROJECT);
    }

    /** 手动 /save,指定作用域(PROJECT / GLOBAL)。返回治理门决策;子 Agent 返回 null。 */
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

    /** 治理门挂起、等人工确认的候选(AGENT 自动沉淀接上后才会有内容)。 */
    public List<MemoryRecord> pendingReview() {
        return longTermMemory != null
                ? longTermMemory.pendingReview()
                : List.of();
    }

    public void clearFacts() {
        if (longTermMemory != null) longTermMemory.clear();
    }

    // ────── 自动沉淀 ──────

    /** 事后扫 transcript,有"失败→修正→成功"则提炼入库。best-effort,失败静默。 */
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
