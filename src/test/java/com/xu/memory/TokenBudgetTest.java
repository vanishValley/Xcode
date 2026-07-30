package com.xu.memory;

import com.xu.llm.LlmClient.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenBudget 测试
 *
 * 覆盖：空历史 / 估算公式 / 压缩判断 / 预算分配
 */
class TokenBudgetTest {

    private TokenBudget budget;

    @BeforeEach
    void setUp() {
        budget = new TokenBudget(128_000);
    }

    @Test
    void emptyHistoryShouldHaveZeroUsage() {
        List<Message> empty = List.of();
        assertEquals(0.0, budget.usagePercent(empty), 0.01);
    }

    @Test
    void shouldEstimateTokensFromCharacterCount() {
        // content 1000 字符 + role "user" 4 字符 = 1004 字符
        // 1004 / 2.5 = 401（向下取整）
        String content = "x".repeat(1000);
        List<Message> msgs = List.of(new Message("user", content));

        long tokens = budget.estimateTokens(msgs);
        assertEquals(401, tokens);
    }

    @Test
    void shouldTriggerCompressWhenOverThreshold() {
        // 造 ~100K Token 的消息（25 万字符），肯定 > 80%
        String big = "x".repeat(250_000);
        List<Message> msgs = List.of(new Message("user", big));

        assertTrue(budget.shouldCompress(msgs));
    }

    @Test
    void shouldNotCompressTinyHistory() {
        List<Message> msgs = List.of(
                new Message("system", "你是一个 AI 助手"),
                new Message("user", "你好")
        );

        assertFalse(budget.shouldCompress(msgs));
    }

    @Test
    void budgetForToolsShouldBePositiveWhenUnderThreshold() {
        // 小历史 → 工具还有大把预算
        List<Message> msgs = List.of(new Message("user", "hello"));
        long toolBudget = budget.budgetForTools(msgs);
        assertTrue(toolBudget > 0);
    }

    @Test
    void budgetForToolsShouldBeZeroWhenOverThreshold() {
        // 大历史 → 工具预算归零（应该先压缩再调工具）
        String big = "x".repeat(300_000);
        List<Message> msgs = List.of(new Message("user", big));
        assertEquals(0, budget.budgetForTools(msgs));
    }
}
