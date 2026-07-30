package com.xu.memory;

import com.xu.llm.LlmClient.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationCompactor 测试
 *
 * 测纯逻辑部分（splitIntoTurns、轮次切分），
 * LLM 压缩部分需要集成测试（暂时用 Mock 方式：调 compact 时会走降级路径）。
 */
class ConversationCompactorTest {

    private ConversationCompactor compactor;
    private TokenBudget tokenBudget;

    @BeforeEach
    void setUp() {
        // 用 null LlmClient → compact() 调 LLM 时会抛异常 → 走降级截断
        tokenBudget = new TokenBudget(128_000);
        compactor = new ConversationCompactor(null, tokenBudget);
    }

    // ────── splitIntoTurns 测试 ──────

    @Test
    void emptyBodyShouldHaveNoTurns() {
        List<List<Message>> turns = compactor.splitIntoTurns(List.of());
        assertTrue(turns.isEmpty());
    }

    @Test
    void singleUserMessageShouldBeOneTurn() {
        List<Message> body = List.of(
                new Message("user", "你好")
        );
        List<List<Message>> turns = compactor.splitIntoTurns(body);
        assertEquals(1, turns.size());
        assertEquals(1, turns.get(0).size());
        assertEquals("你好", turns.get(0).get(0).content);
    }

    @Test
    void twoUserMessagesShouldBeTwoTurns() {
        List<Message> body = List.of(
                new Message("user", "第一个问题"),
                new Message("assistant", "第一个回答"),
                new Message("user", "第二个问题"),
                new Message("assistant", "第二个回答")
        );
        List<List<Message>> turns = compactor.splitIntoTurns(body);
        assertEquals(2, turns.size());

        // 第 1 轮：user + assistant
        assertEquals(2, turns.get(0).size());
        assertEquals("第一个问题", turns.get(0).get(0).content);

        // 第 2 轮：user + assistant
        assertEquals(2, turns.get(1).size());
        assertEquals("第二个问题", turns.get(1).get(0).content);
    }

    @Test
    void turnShouldIncludeToolCallsAndResults() {
        // 一轮 = user → assistant{tool_calls} → tool_result → assistant{content}
        Message userMsg = new Message("user", "读 pom.xml");
        Message assistantCall = new Message("assistant", null);
        assistantCall.toolCalls = List.of();
        Message toolResult = new Message("tool", "<project>...</project>");
        toolResult.toolCallId = "call_1";
        Message assistantReply = new Message("assistant", "pom.xml 有 4 个依赖");

        List<Message> body = List.of(userMsg, assistantCall, toolResult, assistantReply);
        List<List<Message>> turns = compactor.splitIntoTurns(body);

        // 应该只算 1 轮（user→...→assistant content 是一个完整周期）
        assertEquals(1, turns.size());
        assertEquals(4, turns.get(0).size()); // 4 条消息都在同一轮
    }

    @Test
    void systemMessageBetweenTurnsShouldNotSplit() {
        // Plan 模式注入 system 消息后，不应该被误判为新轮
        List<Message> body = List.of(
                new Message("user", "读 pom"),
                new Message("assistant", "读了"),
                new Message("system", "[Plan 执行报告]..."), // injectContext 注入的
                new Message("user", "再读 README"),
                new Message("assistant", "读了 README")
        );

        List<List<Message>> turns = compactor.splitIntoTurns(body);
        assertEquals(2, turns.size());
        // 第 1 轮：user"读 pom" + assistant + system"report"
        assertEquals(3, turns.get(0).size());
        // 第 2 轮：user"再读 README" + assistant
        assertEquals(2, turns.get(1).size());
    }

    // ────── 降级截断测试 ──────

    @Test
    void fallbackWithLlmFailureShouldNotCrash() {
        // LlmClient 是 null → compact() 调压缩时抛 NPE → 走降级截断
        List<Message> history = buildLongHistory(20);

        // 不应该抛异常
        List<Message> result = compactor.compact(history, 20);

        // 应该有 system msg + 降级截断后最近几轮
        assertNotNull(result);
        assertTrue(result.size() > 0);
        assertEquals("system", result.get(0).role); // system prompt 保留
    }

    // ────── 冷却期测试 ──────

    @Test
    void shouldNotCompactWithinCooldown() {
        List<Message> history = buildLongHistory(20);

        // 第 1 次压缩
        compactor.compact(history, 20);
        // 第 2 次：只过了 2 轮，还在冷却期
        List<Message> result = compactor.compact(history, 22);

        // 冷却期内不应触发压缩，返回原列表
        assertEquals(history.size(), result.size());
    }

    // ────── 辅助 ──────

    private List<Message> buildLongHistory(int turns) {
        // 造 N 轮的"大"对话，每一轮 4 条消息
        List<Message> msgs = new java.util.ArrayList<>();
        msgs.add(new Message("system", "你是一个 AI 助手"));
        for (int i = 0; i < turns; i++) {
            msgs.add(new Message("user", "问题 " + i));
            msgs.add(new Message("assistant", "回答 " + i));
            msgs.add(new Message("assistant", "额外分析 " + i));
            msgs.add(new Message("tool", "工具结果 " + i));
        }
        return msgs;
    }
}
