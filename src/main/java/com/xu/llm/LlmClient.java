package com.xu.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(LlmClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://api.deepseek.com";

    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Tracing tracing;

    public LlmClient(String apiKey, String model) {
        this(apiKey, model, Tracing.noop());
    }

    public LlmClient(String apiKey, String model, Tracing tracing) {
        this.apiKey = apiKey;
        this.model = model;
        this.tracing = tracing;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 对话（不带工具）：发 messages，返回 assistant 的文本回复
     */
    public String chat(List<Message> messages) throws IOException {
        Message reply = chatRaw(messages, null);
        return reply.content != null ? reply.content : "";
    }

    /**
     * 对话（带工具）：发 messages + tools，返回完整的 assistant Message。
     * 调用方检查 reply.toolCalls：为空就是文本回答，非空就是模型想调用工具。
     */
    public Message chatRaw(List<Message> messages,
                           List<Map<String, Object>> tools) throws IOException {
        // 每次真实的 HTTP 请求对应一个 Client Span；父 Span 由调用方自动继承。
        // 这里只记录规模和 Token，不记录消息、Prompt、工具定义等正文。
        try (TraceScope scope = tracing.startClient("llm.chat")
                .attribute("gen_ai.model", model)
                .attribute("llm.message_count", messages.size())
                .attribute("llm.tool_definition_count",
                        tools == null ? 0L : tools.size())) {
            try {
                ChatRequest request = new ChatRequest(model, messages, false, tools);
                String json = objectMapper.writeValueAsString(request);
                scope.attribute("llm.request_chars", json.length());

                Request httpRequest = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    scope.attribute("http.status_code", response.code());
                    String body = response.body().string();
                    if (!response.isSuccessful()) {
                        throw new IOException(
                                "LLM API error " + response.code()
                                        + ", response_chars=" + body.length());
                    }

                    ChatResponse chatResponse =
                            objectMapper.readValue(body, ChatResponse.class);
                    if (chatResponse.choices == null
                            || chatResponse.choices.isEmpty()) {
                        throw new IOException("LLM response has no choices");
                    }

                    Choice choice = chatResponse.choices.get(0);
                    scope.attribute("gen_ai.finish_reason", choice.finishReason);
                    choice.message.finishReason = choice.finishReason;
                    if (chatResponse.usage != null) {
                        choice.message.inputTokens =
                                chatResponse.usage.promptTokens;
                        choice.message.outputTokens =
                                chatResponse.usage.completionTokens;
                        choice.message.totalTokens =
                                chatResponse.usage.totalTokens;
                        scope.attribute("gen_ai.input_tokens",
                                chatResponse.usage.promptTokens);
                        scope.attribute("gen_ai.output_tokens",
                                chatResponse.usage.completionTokens);
                        scope.attribute("gen_ai.total_tokens",
                                chatResponse.usage.totalTokens);
                    }
                    logger.atDebug()
                            .addKeyValue("event", "llm.chat.completed")
                            .addKeyValue("model", model)
                            .addKeyValue("finish_reason", choice.finishReason)
                            .addKeyValue("input_tokens",
                                    chatResponse.usage == null
                                            ? -1 : chatResponse.usage.promptTokens)
                            .addKeyValue("output_tokens",
                                    chatResponse.usage == null
                                            ? -1 : chatResponse.usage.completionTokens)
                            .addKeyValue("duration_ms", scope.elapsedMillis())
                            .log("LLM 调用完成");
                    return choice.message;
                }
            } catch (IOException | RuntimeException error) {
                scope.fail(error);
                logger.atError()
                        .addKeyValue("event", "llm.chat.failed")
                        .addKeyValue("model", model)
                        .addKeyValue("error_type",
                                error.getClass().getSimpleName())
                        .setCause(error)
                        .log("LLM 调用失败");
                throw error;
            }
        }
    }

    // ---------------- 数据模型 ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        public String role;       // system / user / assistant / tool
        public String content;
        @JsonProperty("tool_calls")
        public List<ToolCall> toolCalls;     // assistant 调用工具时用
        @JsonProperty("tool_call_id")
        public String toolCallId;            // tool 结果回传时用

        /*
         * 以下字段只服务于本地观测汇总，不属于 Chat Completions 消息协议，
         * 因此不能持久化或在下一轮请求中发送给模型。
         */
        @JsonIgnore
        public long inputTokens;
        @JsonIgnore
        public long outputTokens;
        @JsonIgnore
        public long totalTokens;
        @JsonIgnore
        public String finishReason;

        public Message() {}  // Jackson 需要

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[").append(role).append("] ");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                sb.append("调用工具: ");
                for (ToolCall tc : toolCalls) {
                    sb.append(tc.function.name).append("(")
                      .append(tc.function.arguments.length() > 60
                          ? tc.function.arguments.substring(0, 60) + "..."
                          : tc.function.arguments)
                      .append("), ");
                }
                sb.setLength(sb.length() - 2);
            } else if (content != null) {
                int preview = Math.min(80, content.length());
                sb.append(content.substring(0, preview).replace("\n", "\\n"));
                if (content.length() > 80) {
                    sb.append("...(共").append(content.length()).append("字符)");
                }
            } else {
                sb.append("(空)");
            }
            return sb.toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        public String id;
        public String type = "function";
        public Function function;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {
        public String name;
        public String arguments;  // JSON 字符串
    }

    static class ChatRequest {
        public String model;
        public List<Message> messages;
        public boolean stream = false;

        @JsonProperty("tools")
        public List<Map<String, Object>> tools;

        ChatRequest(String model, List<Message> messages, boolean stream) {
            this(model, messages, stream, null);
        }

        ChatRequest(String model, List<Message> messages, boolean stream,
                    List<Map<String, Object>> tools) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.tools = tools;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatResponse {
        public List<Choice> choices;
        public Usage usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        public Message message;
        @JsonProperty("finish_reason")
        public String finishReason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Usage {
        @JsonProperty("prompt_tokens")
        public long promptTokens;
        @JsonProperty("completion_tokens")
        public long completionTokens;
        @JsonProperty("total_tokens")
        public long totalTokens;
    }
}
