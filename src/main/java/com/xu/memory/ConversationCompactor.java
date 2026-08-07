package com.xu.memory;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 超限时将旧对话压缩为结构化摘要。
 *
 * <p>压缩以完整 user 轮次为边界，不拆开 Tool Call 与 Tool Result；基础 system 消息
 * 不参与摘要。压缩后会重新检查预算，模型调用失败或摘要仍过长时降级为保留最近轮次。
 * 冷却期用于避免频繁压缩本身消耗过多 Token。</p>
 */
public class ConversationCompactor {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCompactor.class);
    /** 压缩后保留最近几轮原文（保证当前工作记忆精度） */
    private static final int KEEP_RECENT_TURNS = 3;

    /** 降级截断时保留的轮数 */
    private static final int FALLBACK_KEEP_TURNS = 5;

    /** 压缩冷却期（轮数） */
    private static final int COOLDOWN_TURNS = 5;

    /** 压缩摘要最大字符数（防止 LLM 输出过长） */
    private static final int MAX_SUMMARY_CHARS = 1500;

    private final LlmClient llmClient;
    private final TokenBudget tokenBudget;

    /** 上次压缩发生在第几轮（总 user 消息计数） */
    private int lastCompactionAtTurn = -1;

    public ConversationCompactor(LlmClient llmClient, TokenBudget tokenBudget) {
        this.llmClient = llmClient;
        this.tokenBudget = tokenBudget;
    }

    // ────── 主入口 ──────

    /**
     * 压缩对话历史。
     *
     * @param history    完整对话历史（含 system prompt）
     * @param totalTurns 当前总共多少轮（用于冷却判断）
     * @return 压缩后的消息列表；如果没触发压缩则返回原列表
     */
    public List<Message> compact(List<Message> history, int totalTurns) {
        // ── 冷却检查 ──
        if (lastCompactionAtTurn >= 0
                && totalTurns - lastCompactionAtTurn < COOLDOWN_TURNS) {
            return history;   // 还在冷却期内，不压缩
        }

        // ── 分离 system + body ──
        // 假设第一条是 system prompt（Agent 构造时保证的）
        Message systemMsg = history.get(0);
        List<Message> body = history.subList(1, history.size());

        // ── 按 user 消息切分轮次 ──
        List<List<Message>> turns = splitIntoTurns(body);
        if (turns.size() <= KEEP_RECENT_TURNS + 2) {
            // 轮次太少，不值得压缩
            return history;
        }

        // ── 分离：旧轮次 → 压缩，最近 KEEP_RECENT_TURNS 轮 → 保留原文 ──
        int splitPoint = turns.size() - KEEP_RECENT_TURNS;
        List<Message> oldMessages = flatten(turns.subList(0, splitPoint));
        List<Message> recentMessages = flatten(turns.subList(splitPoint, turns.size()));

        // ── 路径 A：LLM 压缩 ──
        String summary = null;
        try {
            summary = callLLMForSummary(oldMessages);
        } catch (Exception e) {
            logger.warn("LLM 压缩失败, 降级截断: {}", e.getMessage());
            lastCompactionAtTurn = totalTurns;  // 降级也记录冷却
            return fallbackTruncate(history, systemMsg);
        }

        // ── 拼装压缩后的 history ──
        List<Message> compacted = new ArrayList<>();
        compacted.add(systemMsg);
        // 摘要以 system 角色注入
        compacted.add(new Message("system",
                "[对话历史摘要] 以下是之前对话的要点：\n" + summary));
        compacted.addAll(recentMessages);

        // ── 路径 B：二次 Token 检查 ──
        if (tokenBudget.usagePercent(compacted) > 0.80) {
            logger.warn("压缩后仍超 80%, 强制截断到 {} 轮", FALLBACK_KEEP_TURNS);
            return fallbackTruncate(history, systemMsg);
        }

        lastCompactionAtTurn = totalTurns;

        // 压缩属于内部运行信息，只写结构化日志，不打断 CLI 交互。
        long beforeTokens = tokenBudget.estimateTokens(history);
        long afterTokens = tokenBudget.estimateTokens(compacted);
        double ratio = 1.0 - (double) afterTokens / beforeTokens;
        logger.atInfo()
                .addKeyValue("event", "conversation.compaction.completed")
                .addKeyValue("turns_before", turns.size())
                .addKeyValue("turns_retained", KEEP_RECENT_TURNS)
                .addKeyValue("tokens_before", beforeTokens)
                .addKeyValue("tokens_after", afterTokens)
                .addKeyValue("compression_ratio", ratio)
                .log("对话压缩完成");

        // 调用方持有原列表引用，因此必须原地替换。
        return replaceInPlace(history, compacted);
    }

    // ────── 内部逻辑 ──────

    /**
     * 按 user 消息切分轮次。
     * 一轮 = 从一条 user 消息到下一个 user 消息之前的所有消息。
     *
     * user 消息是稳定的轮次起点；使用 assistant 或 system 作为边界可能拆开工具调用链。
     */
    List<List<Message>> splitIntoTurns(List<Message> body) {
        List<List<Message>> turns = new ArrayList<>();
        List<Message> currentTurn = new ArrayList<>();

        for (Message msg : body) {
            if ("user".equals(msg.role) && !currentTurn.isEmpty()) {
                // 新的一轮开始了，保存当前轮
                turns.add(currentTurn);
                currentTurn = new ArrayList<>();
            }
            currentTurn.add(msg);
        }
        if (!currentTurn.isEmpty()) {
            turns.add(currentTurn);  // 最后一轮
        }
        return turns;
    }

    /**
     * 调 LLM 生成结构化摘要。
     *
     * 压缩 prompt 的关键约束：
     *   - "只总结实际发生的事" — 防幻觉
     *   - "特别提炼用户约束和偏好" — 防约束丢失
     *   - "控制在 N 字以内" — 防输出过长
     *   - "不带工具" — 压缩不需要工具
     */
    private String callLLMForSummary(List<Message> oldMessages) throws Exception {
        // 拼压缩专用 prompt
        String compactPrompt = """
                你是对话摘要专家。下面是 AI 编程助手和用户之间的一段对话历史。

                请用中文提炼为一段简要摘要，覆盖以下要点：
                1. 用户的目标和意图
                2. 已完成的文件操作（读取/创建/修改了哪些文件）
                3. 执行过的命令及其结果
                4. 用户的明确约束、偏好或要求（**这部分非常重要，不要遗漏**）
                5. 仍未完成的事项

                规则：
                - 只总结对话中实际发生的事，不要推测或补全未提及的内容
                - 保持客观简洁，尽量控制在"""
                + MAX_SUMMARY_CHARS + "字以内\n"
                + "- 只输出摘要文本，不要加任何前缀或解释";

        // 拼要压缩的消息为文本（role: content 格式）
        StringBuilder historyText = new StringBuilder();
        for (Message msg : oldMessages) {
            if (msg.content != null && !msg.content.isBlank()) {
                historyText.append("[").append(msg.role).append("]: ")
                        .append(msg.content).append("\n");
            }
            // tool_calls 也概括一下
            if (msg.toolCalls != null) {
                for (var tc : msg.toolCalls) {
                    historyText.append("[assistant→tool]: ")
                            .append(tc.function.name)
                            .append("(").append(tc.function.arguments).append(")\n");
                }
            }
        }

        // 调 LLM（不带工具——压缩是纯思考任务）
        List<Message> request = List.of(
                new Message("system", compactPrompt),
                new Message("user", "请为以下对话生成摘要：\n\n"
                        + historyText.toString())
        );

        Message reply = llmClient.chatRaw(request, null);
        String summary = reply.content != null ? reply.content : "";

        // 截断过长输出（二次兜底）
        if (summary.length() > MAX_SUMMARY_CHARS * 2) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS * 2) + "...[已截断]";
        }

        return summary;
    }

    /**
     * 降级路径：简单截断。
     * 当 LLM 压缩失败或压缩后仍然超限时使用。
     */
    private List<Message> fallbackTruncate(List<Message> history, Message systemMsg) {
        // 先把要保留的内容物化到 truncated，再原地替换 history。
        // body 是 history 的 subList 视图，必须在动 history 之前用完。
        List<Message> body = history.subList(1, history.size());
        List<List<Message>> turns = splitIntoTurns(body);

        List<Message> truncated = new ArrayList<>();
        truncated.add(systemMsg);   // system prompt 必须保持在第一条
        truncated.add(new Message("system",
                "[注意] 对话历史因过长已被截断，之前的部分已丢弃。"));

        int start = Math.max(0, turns.size() - FALLBACK_KEEP_TURNS);
        for (int i = start; i < turns.size(); i++) {
            truncated.addAll(turns.get(i));
        }

        logger.atWarn()
                .addKeyValue("event", "conversation.truncation.completed")
                .addKeyValue("turns_before", turns.size())
                .addKeyValue("turns_retained", FALLBACK_KEEP_TURNS)
                .log("对话已降级截断");
        return replaceInPlace(history, truncated);
    }

    /**
     * 原地替换 target 的内容为 newContent，并返回 target 本身。
     *
     * 为什么原地改而不返回新对象？
     *   Agent.history 是 final 字段、被多处持有引用。压缩若返回新对象，
     *   调用方 (Agent.run) 要么因 final 接不住、要么忘了接（本次真实 bug），
     *   压缩结果被丢弃、上下文无限增长。原地改让所有持有 history
     *   引用的地方都看到压缩后的结果。
     *
     * 先对 newContent 做快照再 clear：newContent 里的 Message 引用可能
     *   来自 history 的 subList 视图，直接 clear 会让视图失效。
     */
    private static List<Message> replaceInPlace(List<Message> target, List<Message> newContent) {
        List<Message> snapshot = new ArrayList<>(newContent);
        target.clear();
        target.addAll(snapshot);
        return target;
    }

    /** 扁平化轮次列表 */
    private static List<Message> flatten(List<List<Message>> turns) {
        List<Message> result = new ArrayList<>();
        for (List<Message> turn : turns) {
            result.addAll(turn);
        }
        return result;
    }

    /** 重置压缩冷却（/clear 时调用） */
    public void reset() {
        lastCompactionAtTurn = -1;
    }
}
