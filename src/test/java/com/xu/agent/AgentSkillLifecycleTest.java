package com.xu.agent;

import com.xu.config.XcodePaths;
import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Function;
import com.xu.llm.LlmClient.Message;
import com.xu.llm.LlmClient.ToolCall;
import com.xu.memory.MemoryManager;
import com.xu.skill.SkillRegistry;
import com.xu.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillLifecycleTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldKeepSkillActiveDuringTaskAndReleaseBodyAfterwards()
            throws Exception {
        SkillRegistry skills = new SkillRegistry(new XcodePaths(projectRoot));
        skills.reload();
        ToolRegistry tools = new ToolRegistry();
        tools.registerLoadSkillTool(skills);
        AtomicInteger calls = new AtomicInteger();

        LlmClient client = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(
                    List<Message> messages,
                    List<Map<String, Object>> toolDefinitions) {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    assertTrue(messages.get(0).content.contains("你是一个有用的"));
                    assertFalse(messages.get(0).content.contains("## 可用 Skills"));
                    assertTrue(messages.get(1).content.startsWith("## 可用 Skills"));
                    assertEquals("user", messages.get(messages.size() - 1).role);
                    return loadSkillReply("call-skill", "web-access");
                }

                assertTrue(messages.stream().anyMatch(message ->
                        "tool".equals(message.role)
                                && message.content.contains("已加载 Skill: web-access")
                                && message.content.contains("工具路由")));
                return new Message("assistant", "done");
            }
        };

        Agent agent = new Agent(client, tools, new MemoryManager(), skills);

        assertEquals("done", agent.run("查询需要联网的资料"));
        assertEquals(2, calls.get());
        Message persistedToolResult = agent.getHistory().stream()
                .filter(message -> "tool".equals(message.role))
                .findFirst()
                .orElseThrow();
        assertTrue(persistedToolResult.content.startsWith("【Skill 已释放】"));
        assertFalse(persistedToolResult.content.contains("工具路由"));
    }

    private static Message loadSkillReply(String id, String skillName) {
        ToolCall call = new ToolCall();
        call.id = id;
        call.function = new Function();
        call.function.name = "load_skill";
        call.function.arguments = "{\"name\":\"" + skillName + "\"}";
        Message reply = new Message("assistant", null);
        reply.toolCalls = List.of(call);
        return reply;
    }
}
