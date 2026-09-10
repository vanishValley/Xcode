package com.xu.memory;

import com.xu.llm.LlmClient.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerPromptTest {

    @Test
    void shouldAssemblePromptInStableThenDynamicOrder() {
        LongTermMemory longTermMemory = LongTermMemory.inMemory();
        longTermMemory.save(MemoryRecord.create(
                "升级 okhttp 前先检查兼容性",
                MemoryScope.PROJECT,
                "/p",
                MemorySource.HUMAN,
                0.9));
        MemoryManager memory =
                new MemoryManager(longTermMemory, "/p");
        memory.setGoal("完成依赖升级");
        memory.setContext("【Plan上下文】已经读取 pom.xml");
        memory.beginTask("升级 okhttp");

        List<Message> history = List.of(
                new Message("system", "基础提示词"),
                new Message("user", "升级 okhttp"));
        List<Message> prompt = memory.assemblePrompt(
                history,
                "Skill索引");

        assertEquals("基础提示词", prompt.get(0).content);
        assertEquals("Skill索引", prompt.get(1).content);
        assertTrue(prompt.get(2).content.contains("当前目标"));
        assertTrue(prompt.get(3).content.contains("相关记忆"));
        assertTrue(prompt.get(4).content.contains("Plan上下文"));
        assertEquals("user", prompt.get(5).role);
        assertEquals(2, history.size(), "临时索引不应污染原始历史");
    }

    @Test
    void shouldFreezeRetrievedMemoryForWholeReactTask() {
        LongTermMemory longTermMemory = LongTermMemory.inMemory();
        longTermMemory.save(MemoryRecord.create(
                "okhttp 升级要运行测试",
                MemoryScope.PROJECT,
                "/p",
                MemorySource.HUMAN,
                0.9));
        MemoryManager memory =
                new MemoryManager(longTermMemory, "/p");
        memory.beginTask("升级 okhttp");

        longTermMemory.save(MemoryRecord.create(
                "okhttp 升级后检查代理配置",
                MemoryScope.PROJECT,
                "/p",
                MemorySource.HUMAN,
                0.9));

        List<Message> prompt = memory.assemblePrompt(List.of(
                new Message("system", "system"),
                new Message("user", "升级 okhttp")));
        String memoryBlock = prompt.get(1).content;

        assertTrue(memoryBlock.contains("运行测试"));
        assertFalse(memoryBlock.contains("代理配置"));
    }
}
