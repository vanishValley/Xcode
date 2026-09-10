package com.xu.skill;

import com.xu.llm.LlmClient.Function;
import com.xu.llm.LlmClient.Message;
import com.xu.llm.LlmClient.ToolCall;
import com.xu.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTaskScopeTest {

    @Test
    void shouldInjectFullSkillBodyOnlyOncePerTask() {
        SkillTaskScope scope = new SkillTaskScope();

        ToolExecutionResult first = scope.record(
                loadSkillCall("call-1", "prompt-change-evaluation"),
                ToolExecutionResult.success(
                        "## 已加载 Skill: prompt-change-evaluation\n\n完整正文"));
        ToolExecutionResult duplicate = scope.record(
                loadSkillCall("call-2", "prompt-change-evaluation"),
                ToolExecutionResult.success(
                        "## 已加载 Skill: prompt-change-evaluation\n\n完整正文"));

        assertTrue(first.content().contains("完整正文"));
        assertTrue(duplicate.content().contains("已在当前任务中激活"));
        assertTrue(!duplicate.content().contains("\n\n完整正文"));
        assertEquals(
                List.of("prompt-change-evaluation"),
                scope.loadedNames().stream().sorted().toList());
    }

    @Test
    void shouldReleaseBodyButKeepToolProtocolPair() {
        Message assistant = new Message("assistant", null);
        assistant.toolCalls = List.of(
                loadSkillCall("call-1", "prompt-change-evaluation"));
        Message tool = new Message(
                "tool",
                "## 已加载 Skill: prompt-change-evaluation\n\n完整正文");
        tool.toolCallId = "call-1";
        List<Message> history = new ArrayList<>(List.of(assistant, tool));

        int released = SkillTaskScope.releaseBodies(history);

        assertEquals(1, released);
        assertEquals(2, history.size());
        assertEquals("call-1", history.get(1).toolCallId);
        assertTrue(history.get(1).content.startsWith("【Skill 已释放】"));
        assertTrue(!history.get(1).content.contains("\n\n完整正文"));
        assertEquals(0, SkillTaskScope.releaseBodies(history), "释放应幂等");
    }

    @Test
    void shouldNotRewriteFailedLoadResult() {
        Message assistant = new Message("assistant", null);
        assistant.toolCalls = List.of(loadSkillCall("call-1", "missing"));
        Message tool = new Message("tool", "Skill 'missing' 不存在");
        tool.toolCallId = "call-1";
        List<Message> history = new ArrayList<>(List.of(assistant, tool));

        assertEquals(0, SkillTaskScope.releaseBodies(history));
        assertEquals("Skill 'missing' 不存在", tool.content);
    }

    private static ToolCall loadSkillCall(String id, String name) {
        ToolCall call = new ToolCall();
        call.id = id;
        call.function = new Function();
        call.function.name = "load_skill";
        call.function.arguments = "{\"name\":\"" + name + "\"}";
        return call;
    }
}
