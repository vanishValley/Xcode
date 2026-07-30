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
 *   KnowledgeBase         — 长期知识(存储 + 检索 + 治理门)
 *   TokenBudget           — Token 估算
 *   ConversationCompactor — 对话压缩
 *
 * Agent 每轮调用:
 *   assemblePrompt(history) → 组装本轮 prompt(目标+知识+上下文+历史),返回新 List
 *   compactIfNeeded(history) → 检查并原地压缩历史
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
    private final KnowledgeBase knowledgeBase;
    private final TokenBudget tokenBudget;
    private final ConversationCompactor compactor;

    private final String projectPath;
    private int totalTurns = 0;

    /** 任务状态:目标锚点(每轮重贴防偏移) + 外部上下文(Main 回灌的 plan 报告) */
    private String taskGoal;
    private String taskContext;

    /** 主 Agent 用的完整构造 */
    public MemoryManager(SessionStore sessionStore,
                         KnowledgeBase knowledgeBase,
                         LlmClient llmClient,
                         String projectPath) {
        this.sessionStore = sessionStore;
        this.knowledgeBase = knowledgeBase;
        this.tokenBudget = new TokenBudget();
        this.compactor = new ConversationCompactor(llmClient, tokenBudget);
        this.projectPath = projectPath;
    }

    /** 子 Agent 用的构造（无持久化、无压缩） */
    public MemoryManager() {
        this.sessionStore = null;
        this.knowledgeBase = null;
        this.tokenBudget = new TokenBudget();
        this.compactor = null;
        this.projectPath = null;
    }

    /** 子 Agent 用的构造:共享长期知识检索 + 目标锚点,无会话落盘/压缩(子任务历史短) */
    public MemoryManager(KnowledgeBase knowledgeBase, String projectPath) {
        this.sessionStore = null;
        this.knowledgeBase = knowledgeBase;
        this.tokenBudget = new TokenBudget();
        this.compactor = null;
        this.projectPath = projectPath;
    }

    // ────── 任务状态 ──────

    public void setGoal(String goal) { this.taskGoal = goal; }
    public void setContext(String ctx) { this.taskContext = ctx; }
    public boolean hasGoal() { return taskGoal != null && !taskGoal.isEmpty(); }
    /** 清空任务状态。 */
    public void clearTask() { this.taskGoal = null; this.taskContext = null; }

    // ────── 每轮统一组装(收敛散落注入,不污染干净历史) ──────

    /**
     * 从干净历史 + 注入块组装本轮 prompt,返回新 List。
     * 注入块不进干净历史 → session 落盘不被污染 → 不再需要 removeIf 擦除。
     */
    public List<Message> assemblePrompt(List<Message> cleanHistory) {
        totalTurns++;
        List<Message> prompt = new ArrayList<>();

        // 1. 任务目标(放最前,每轮重贴,防偏移)
        if (taskGoal != null && !taskGoal.isEmpty()) {
            prompt.add(new Message("system", "【当前目标】" + taskGoal));
        }

        // 2. 长期知识(用最近一条 user 消息做检索查询)
        String lastUserContent = null;
        for (int i = cleanHistory.size() - 1; i >= 0; i--) {
            Message m = cleanHistory.get(i);
            if ("user".equals(m.role) && m.content != null) {
                lastUserContent = m.content;
                break;
            }
        }
        if (knowledgeBase != null && projectPath != null && lastUserContent != null) {
            List<MemoryRecord> relevant = knowledgeBase.retrieve(lastUserContent, projectPath);
            if (!relevant.isEmpty()) {
                logger.debug("记忆检索: 命中 {} 条, 查询=\"{}\"",
                        relevant.size(),
                        lastUserContent.length() > 60
                                ? lastUserContent.substring(0, 60) + "..."
                                : lastUserContent);
                for (MemoryRecord r : relevant) {
                    logger.debug("  → {} (score by {})", r.content(), r.source());
                }
                StringBuilder sb = new StringBuilder("## 相关记忆\n");
                int chars = 0;
                for (MemoryRecord r : relevant) {
                    String line = "- " + r.content() + "\n";
                    if (chars + line.length() > MAX_CONTEXT_CHARS) break;
                    sb.append(line);
                    chars += line.length();
                }
                prompt.add(new Message("system", sb.toString()));
            }
        }

        // 3. 外部上下文(plan 执行报告,有则贴记忆之后历史之前)
        if (taskContext != null && !taskContext.isEmpty()) {
            prompt.add(new Message("system", taskContext));
        }

        // 4. 干净历史(纯对话,无注入)
        prompt.addAll(cleanHistory);

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
    public GovernanceGate.Decision saveFact(String content) {
        return saveFact(content, MemoryScope.PROJECT);
    }

    /** 手动 /save,指定作用域(PROJECT / GLOBAL)。返回治理门决策;子 Agent 返回 null。 */
    public GovernanceGate.Decision saveFact(String content, MemoryScope scope) {
        if (knowledgeBase == null) return null;
        return knowledgeBase.saveHuman(content, scope, projectPath);
    }

    public List<MemoryRecord> listFacts() {
        return knowledgeBase != null ? knowledgeBase.list(projectPath) : List.of();
    }

    /** 治理门挂起、等人工确认的候选(AGENT 自动沉淀接上后才会有内容)。 */
    public List<MemoryRecord> pendingReview() {
        return knowledgeBase != null ? knowledgeBase.pendingReview() : List.of();
    }

    public void clearFacts() {
        if (knowledgeBase != null) knowledgeBase.clear();
    }

    // ────── 自动沉淀 ──────

    /** 事后扫 transcript,有"失败→修正→成功"则提炼入库。best-effort,失败静默。 */
    public void tryAutoExtract(List<Message> history, LlmClient llmClient) {
        if (knowledgeBase == null || projectPath == null) return;
        LessonExtractor.tryExtract(history, llmClient, knowledgeBase, projectPath);
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
