package com.xu.memory;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动沉淀:事后扫 transcript,检测"失败→修正→成功"模式,调 LLM 提炼一句话教训。
 *
 * 两级漏斗:
 *   ① 信号闸门 hasFailureThenSuccess()  — 扫 tool 消息,不调 LLM,O(n),零成本
 *   ② LLM 提炼 extract()               — 调一次 LLM,"有值得记的吗?没有说无"
 *
 * 入口:tryExtract() 一站式,提炼失败/返回"无"时静默跳过。
 * 调用方:PlanExecuteAgent(子任务完成后)+ Agent.run()(ReAct 返回前)。
 */
public final class LessonExtractor {

    private LessonExtractor() {} // 工具类

    // ── ① 信号闸门 ──

    /**
     * 扫 history 里的 tool 消息,判断是否有"同一个工具先出错、后成功"的模式。
     * 不调 LLM,纯字符串扫描,微秒级。
     */
    public static boolean hasFailureThenSuccess(List<Message> history) {
        Set<String> errored = new HashSet<>(); // 哪些工具出过错

        for (Message m : history) {
            if (!"tool".equals(m.role) || m.content == null) continue;
            String toolName = m.toolCallId != null ? extractToolName(m.toolCallId) : "";

            if (isError(m.content)) {
                errored.add(toolName);             // 记录:这个工具出过错
            } else if (errored.contains(toolName)) {
                return true;                       // 之前出错,现在成功了 → 命中
            }
        }
        return false;
    }

    // ── ② LLM 提炼 ──

    /**
     * 调 LLM 从失败→成功的过程中提炼一句话教训。
     * @return 一句话教训;返回 null 或 "无" 表示无有效内容
     */
    public static String extract(List<Message> history, LlmClient llmClient) {
        // 只取 tool 消息的摘要,不传全量(省 token)
        String transcript = summarizeToolMessages(history);
        if (transcript.isEmpty()) return null;

        String prompt = """
                下面是 agent 执行任务时工具调用的过程记录,其中经历了失败然后修正成功。
                请判断是否有值得记住的经验教训(如环境配置、项目约定、踩过的坑)。
                如果有,用一句话总结(不超过 80 字);如果没有,只回答"无"。""";

        try {
            List<Message> messages = List.of(
                    new Message("system", prompt),
                    new Message("user", transcript));
            Message reply = llmClient.chatRaw(messages, null);
            if (reply.content == null || reply.content.isBlank()) return null;
            String lesson = reply.content.strip();
            if (lesson.startsWith("\"")) lesson = lesson.substring(1);
            if (lesson.endsWith("\"")) lesson = lesson.substring(0, lesson.length() - 1);
            return lesson.trim();
        } catch (Exception e) {
            // best-effort:提炼失败当无事发生
            return null;
        }
    }

    // ── 一站式入口 ──

    /**
     * 完整流水线:有信号 → 提炼 → 有效 → 入库(去重+覆盖+数量限制)。
     * 任一步失败静默跳过,不抛异常。
     */
    public static void tryExtract(List<Message> history, LlmClient llmClient,
                                  KnowledgeBase kb, String projectPath) {
        try {
            if (!hasFailureThenSuccess(history)) return;

            String lesson = extract(history, llmClient);
            if (lesson == null || "无".equals(lesson) || lesson.isBlank()) return;

            kb.saveAgent(lesson, projectPath);
        } catch (Exception ignored) {
            // best-effort:任何一步异常都不影响主流程
        }
    }

    // ── 内部 ──

    /** tool_call_id 通常形如 "call_xxx",从中提取前缀做简单分组。 */
    private static String extractToolName(String toolCallId) {
        if (toolCallId == null || toolCallId.isEmpty()) return "";
        // 简单截断:取 "_" 之前的部分做粗分组
        int idx = toolCallId.indexOf('_');
        return idx > 0 ? toolCallId.substring(0, idx) : toolCallId;
    }

    /** tool result 是不是报错 */
    private static boolean isError(String content) {
        String lower = content.toLowerCase();
        return lower.contains("工具执行出错")
                || lower.contains("工具不存在")
                || lower.startsWith("error")
                || lower.contains("失败");
    }

    /** 取 tool 消息摘要:每条只留前 120 字 + 标记是否报错。 */
    private static String summarizeToolMessages(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Message m : history) {
            if (!"tool".equals(m.role) || m.content == null) continue;
            count++;
            String tag = isError(m.content) ? "[失败]" : "[成功]";
            String body = m.content.length() > 120
                    ? m.content.substring(0, 120) + "..."
                    : m.content;
            sb.append(count).append(". ").append(tag).append(" ").append(body).append("\n");
        }
        return sb.toString();
    }
}
