package com.xu.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 关键词检索:分词 / 打分 / top-K 相关性。 */
class KeywordRetrieverTest {

    private MemoryRecord rec(String content) {
        return MemoryRecord.create(content, MemoryScope.PROJECT, "/p", MemorySource.HUMAN, 0.9);
    }

    @Test
    void jiebaShouldSplitChineseWords() {
        Set<String> tokens = KeywordRetriever.tokenize("帮我升级 okhttp 到最新版本");
        assertTrue(tokens.contains("升级"));
        assertTrue(tokens.contains("okhttp"));
        assertTrue(tokens.contains("最新"));
        // 单字 "帮""我" 应被过滤
        assertFalse(tokens.contains("帮"));
        assertFalse(tokens.contains("我"));
    }

    @Test
    void emptyQueryShouldReturnEmptyTokens() {
        assertTrue(KeywordRetriever.tokenize("").isEmpty());
    }

    @Test
    void scoreShouldIncreaseWithMoreMatches() {
        long now = System.currentTimeMillis();
        Set<String> tokens = Set.of("升级", "okhttp");

        double good = KeywordRetriever.score(rec("升级 okhttp 到 5.0"), tokens, now);      // 两个都命中
        double partial = KeywordRetriever.score(rec("昨天升级了依赖"), tokens, now);       // 命中一个
        double unrelated = KeywordRetriever.score(rec("用户喜欢用 vscode"), tokens, now);  // 0

        assertTrue(good > partial);
        assertEquals(0.0, unrelated, 0.01);
    }

    @Test
    void retrieveShouldReturnRelevantTopK() {
        KnowledgeStore store = new KnowledgeStore();
        store.put(rec("okhttp 依赖已升级到 5.0"));
        store.put(rec("用户偏好 Java 17"));
        store.put(rec("上次创建了 Spring Boot 项目"));

        Retriever retriever = new KeywordRetriever(store);
        List<MemoryRecord> result = retriever.retrieve("升级 okhttp", "/p", 3);

        assertFalse(result.isEmpty());
        assertTrue(result.get(0).content().contains("okhttp"));
    }

    @Test
    void retrieveShouldExcludeOtherProjects() {
        KnowledgeStore store = new KnowledgeStore();
        store.put(MemoryRecord.create("okhttp 升级到 5.0", MemoryScope.PROJECT, "/a", MemorySource.HUMAN, 0.9));

        Retriever retriever = new KeywordRetriever(store);
        // 在 /b 仓库检索,拿不到 /a 的项目专属记忆
        assertTrue(retriever.retrieve("升级 okhttp", "/b", 3).isEmpty());
    }
}
