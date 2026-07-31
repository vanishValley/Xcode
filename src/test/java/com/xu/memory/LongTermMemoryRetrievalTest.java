package com.xu.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 长期记忆内部的关键词分词、打分和作用域过滤。 */
class LongTermMemoryRetrievalTest {

    private MemoryRecord rec(String content) {
        return MemoryRecord.create(content, MemoryScope.PROJECT, "/p", MemorySource.HUMAN, 0.9);
    }

    @Test
    void jiebaShouldSplitChineseWords() {
        Set<String> tokens =
                LongTermMemory.tokenize("帮我升级 okhttp 到最新版本");
        assertTrue(tokens.contains("升级"));
        assertTrue(tokens.contains("okhttp"));
        assertTrue(tokens.contains("最新"));
        // 单字 "帮""我" 应被过滤
        assertFalse(tokens.contains("帮"));
        assertFalse(tokens.contains("我"));
    }

    @Test
    void emptyQueryShouldReturnEmptyTokens() {
        assertTrue(LongTermMemory.tokenize("").isEmpty());
    }

    @Test
    void scoreShouldIncreaseWithMoreMatches() {
        long now = System.currentTimeMillis();
        Set<String> tokens = Set.of("升级", "okhttp");

        double good = LongTermMemory.score(
                rec("升级 okhttp 到 5.0"), tokens, now);
        double partial = LongTermMemory.score(
                rec("昨天升级了依赖"), tokens, now);
        double unrelated = LongTermMemory.score(
                rec("用户喜欢用 vscode"), tokens, now);

        assertTrue(good > partial);
        assertEquals(0.0, unrelated, 0.01);
    }

    @Test
    void retrieveShouldReturnRelevantTopK() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(rec("okhttp 依赖已升级到 5.0"));
        memory.save(rec("用户偏好 Java 17"));
        memory.save(rec("上次创建了 Spring Boot 项目"));

        List<MemoryRecord> result =
                memory.retrieve("升级 okhttp", "/p", 3);

        assertFalse(result.isEmpty());
        assertTrue(result.get(0).content().contains("okhttp"));
    }

    @Test
    void retrieveShouldExcludeOtherProjects() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(MemoryRecord.create(
                "okhttp 升级到 5.0",
                MemoryScope.PROJECT,
                "/a",
                MemorySource.HUMAN,
                0.9));

        assertTrue(memory.retrieve("升级 okhttp", "/b", 3).isEmpty());
    }
}
