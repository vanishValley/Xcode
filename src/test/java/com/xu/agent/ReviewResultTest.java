package com.xu.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ReviewResult JSON 解析的防御逻辑测试。 */
class ReviewResultTest {

    @Test
    void shouldParseApprovedWithEmptyIssues() {
        String json = "{\"approved\": true, \"issues\": [], \"suggestions\": []}";
        ReviewResult r = ReviewResult.parse(json);
        assertTrue(r.approved());
        assertTrue(r.issues().isEmpty());
        assertTrue(r.suggestions().isEmpty());
    }

    @Test
    void shouldParseRejectedWithMultipleIssues() {
        String json = "{\"approved\": false, \"issues\": [\"缺少 @Valid 注解\", \"误删了 import\"], \"suggestions\": []}";
        ReviewResult r = ReviewResult.parse(json);
        assertFalse(r.approved());
        assertEquals(2, r.issues().size());
        assertTrue(r.issues().contains("缺少 @Valid 注解"));
    }

    @Test
    void shouldHandleSingleStringInsteadOfArray() {
        // LLM 经常在只有一条 issue 时写成字符串而非数组
        String json = "{\"approved\": false, \"issues\": \"缺少依赖\", \"suggestions\": []}";
        ReviewResult r = ReviewResult.parse(json);
        assertFalse(r.approved());
        assertEquals(1, r.issues().size());
        assertEquals("缺少依赖", r.issues().get(0));
    }

    @Test
    void shouldDefaultToApprovedWhenFieldMissing() {
        String json = "{\"issues\": [], \"suggestions\": []}";
        ReviewResult r = ReviewResult.parse(json);
        assertTrue(r.approved(), "approved 字段缺失应默认通过");
    }

    @Test
    void shouldFallbackOnPlainText() {
        // 彻底不是 JSON —— 宽容放行
        ReviewResult r = ReviewResult.parse("通过");
        assertTrue(r.approved());
    }

    @Test
    void shouldFallbackOnMarkdownWrappedJson() {
        // LLM 输出带 ```json 包裹，Planner.extractJson 能处理
        String text = "```json\n{\"approved\": true, \"issues\": [], \"suggestions\": []}\n```";
        ReviewResult r = ReviewResult.parse(text);
        assertTrue(r.approved());
    }
}
