package com.xu.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.llm.LlmClient.Message;
import com.xu.llm.LlmClient.ToolCall;
import com.xu.tool.ToolExecutionResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 Agent 任务内的 Skill 激活上下文。
 *
 * <p>SkillRegistry 在程序级缓存 Skill 定义；本类只跟踪当前任务
 * 已经注入模型上下文的 Skill。同名 Skill 在一次 ReAct 任务中只注入
 * 一次完整正文；任务结束后将正文替换为短标记，既保持
 * Chat Completions 的 tool_call/tool 配对，又不让 Skill 正文污染后续任务。</p>
 */
public final class SkillTaskScope {

    private static final String LOADED_PREFIX = "## 已加载 Skill: ";
    private static final String DUPLICATE_PREFIX = "## Skill 已在当前任务中激活: ";
    private static final String RELEASED_PREFIX = "【Skill 已释放】";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> loadedNames = new HashSet<>();

    /**
     * 记录一次 load_skill 结果，并将同任务内的重复加载转为幂等结果。
     */
    public ToolExecutionResult record(
            ToolCall call,
            ToolExecutionResult execution) {
        if (!isLoadSkill(call) || execution == null || !execution.success()) {
            return execution;
        }

        String name = skillName(call);
        if (name == null || name.isBlank()) {
            return execution;
        }

        if (loadedNames.add(name)) {
            return execution;
        }

        return ToolExecutionResult.success(
                DUPLICATE_PREFIX + name
                        + "\n\n完整指引已在当前任务上下文中，"
                        + "请继续按照已加载的工作流执行。");
    }

    /**
     * 释放历史中已完成任务的 Skill 正文。
     *
     * <p>不删除 tool 消息，因为删除后会让 assistant.tool_calls 失去
     * 对应结果，导致后续 Chat Completions 请求不符合协议。</p>
     *
     * @return 被替换的 Skill 工具结果数量
     */
    public static int releaseBodies(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }

        Map<String, String> skillCalls = findSkillCalls(history);
        int released = 0;
        for (Message message : history) {
            if (!"tool".equals(message.role)
                    || message.toolCallId == null
                    || message.content == null) {
                continue;
            }

            String name = skillCalls.get(message.toolCallId);
            if (name == null || message.content.startsWith(RELEASED_PREFIX)) {
                continue;
            }
            if (!message.content.startsWith(LOADED_PREFIX)
                    && !message.content.startsWith(DUPLICATE_PREFIX)) {
                continue;
            }

            message.content = RELEASED_PREFIX + name
                    + " 已在上一任务中应用；"
                    + "完整正文已从活动上下文移除，"
                    + "新任务需要时请重新调用 load_skill。";
            released++;
        }
        return released;
    }

    public Set<String> loadedNames() {
        return Set.copyOf(loadedNames);
    }

    private static Map<String, String> findSkillCalls(
            List<Message> history) {
        Map<String, String> calls = new HashMap<>();
        SkillTaskScope parser = new SkillTaskScope();
        for (Message message : history) {
            if (!"assistant".equals(message.role)
                    || message.toolCalls == null) {
                continue;
            }
            for (ToolCall call : message.toolCalls) {
                if (call == null || call.id == null
                        || !isLoadSkill(call)) {
                    continue;
                }
                String name = parser.skillName(call);
                if (name != null && !name.isBlank()) {
                    calls.put(call.id, name);
                }
            }
        }
        return calls;
    }

    private static boolean isLoadSkill(ToolCall call) {
        return call != null
                && call.function != null
                && "load_skill".equals(call.function.name);
    }

    private String skillName(ToolCall call) {
        String arguments = call.function.arguments;
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> values = objectMapper.readValue(
                    arguments,
                    new TypeReference<Map<String, Object>>() {});
            Object name = values.get("name");
            return name == null ? null : name.toString();
        } catch (Exception ignored) {
            // ToolExecutor 负责报告参数错误；生命周期跟踪不改写执行结果。
            return null;
        }
    }
}
