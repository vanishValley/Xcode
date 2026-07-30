package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reviewer 控制流测试:鉴的是兜底逻辑,不是 LLM 质量。 */
class ReviewerTest {

    /** LLM 直接返回结论(无工具调用) → 正常解析 */
    @Test
    void shouldParseDirectJsonConclusion() {
        LlmClient mock = mockClient(
                msg("assistant", "{\"approved\": true, \"issues\": [], \"suggestions\": []}")
        );
        Reviewer reviewer = new Reviewer(mock);
        ReviewResult r = reviewer.review("需求", "子任务", "Worker 汇报已完成");
        assertTrue(r.approved());
    }

    /** LLM 先调工具再返回结论 → 控制流正常 */
    @Test
    void shouldHandleToolCallsThenConclude() {
        LlmClient mock = new LlmClient("test", "test") {
            int count = 0;

            @Override
            public Message chatRaw(java.util.List<Message> messages,
                                   java.util.List<java.util.Map<String, Object>> tools) throws IOException {
                count++;
                Message m = new Message();
                m.role = "assistant";
                if (count <= 2) {
                    // 前 2 轮调工具
                    m.toolCalls = List.of(new LlmClient.ToolCall() {{
                        id = "call_" + count;
                        function = new Function() {{
                            name = "read_file";
                            arguments = "{\"path\":\"pom.xml\"}";
                        }};
                    }});
                } else {
                    // 第 3 轮返回结论
                    m.content = "{\"approved\": true, \"issues\": [], \"suggestions\": []}";
                }
                return m;
            }
        };
        Reviewer reviewer = new Reviewer(mock);
        ReviewResult r = reviewer.review("需求", "子任务", "Worker 汇报已完成");
        assertTrue(r.approved());  // 即使工具因无真实文件而失败,控制流不崩
    }

    /** LLM 调 IOException → 宽容放行 */
    @Test
    void shouldTolerateLlmIOException() {
        LlmClient mock = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(java.util.List<Message> messages,
                                   java.util.List<java.util.Map<String, Object>> tools) throws IOException {
                throw new IOException("network error");
            }
        };
        Reviewer reviewer = new Reviewer(mock);
        ReviewResult r = reviewer.review("需求", "子任务", "Worker 汇报已完成");
        assertTrue(r.approved(), "审察者故障不阻塞业务");
    }

    /** 跑满 3 轮没给结论 → 兜底解析最后一轮 */
    @Test
    void shouldFallbackAfterMaxTurns() {
        LlmClient mock = new LlmClient("test", "test") {
            @Override
            public Message chatRaw(java.util.List<Message> messages,
                                   java.util.List<java.util.Map<String, Object>> tools) throws IOException {
                // 每轮都只调工具不给结论,跑满 3 轮
                Message m = new Message();
                m.role = "assistant";
                m.toolCalls = List.of(new LlmClient.ToolCall() {{
                    id = "call_x";
                    function = new Function() {{
                        name = "list_dir";
                        arguments = "{\"path\":\".\"}";
                    }};
                }});
                return m;
            }
        };
        Reviewer reviewer = new Reviewer(mock);
        ReviewResult r = reviewer.review("需求", "子任务", "Worker 汇报已完成");
        // 兜底解析:最后一轮没有 content → parse("")返回默认通过
        assertTrue(r.approved());
    }

    // ---- 辅助 ----

    private static LlmClient mockClient(Message... responses) {
        return new LlmClient("test", "test") {
            int idx = 0;

            @Override
            public Message chatRaw(java.util.List<Message> messages,
                                   java.util.List<java.util.Map<String, Object>> tools) throws IOException {
                if (idx >= responses.length) {
                    // 超出预制响应,返回默认结论
                    Message m = new Message();
                    m.role = "assistant";
                    m.content = "{\"approved\": true, \"issues\": [], \"suggestions\": []}";
                    return m;
                }
                return responses[idx++];
            }
        };
    }

    private static Message msg(String role, String content) {
        Message m = new Message();
        m.role = role;
        m.content = content;
        return m;
    }
}
