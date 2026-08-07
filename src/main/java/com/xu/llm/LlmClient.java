package com.xu.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.observability.ExecutionArtifactStore;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * DeepSeek Chat Completions 客户端，支持普通请求、SSE 流式文本、碎片化 Tool Call 重组
 * 和主动取消。观测数据只记录模型、用量和内容长度，不记录 Prompt 正文。
 */
public class LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(LlmClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://api.deepseek.com";

    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Tracing tracing;
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();

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
                .attribute("gen_ai.operation.name", "chat")
                .attribute("gen_ai.provider.name", "deepseek")
                .attribute("gen_ai.request.model", model)
                .attribute("llm.message_count", messages.size())
                .attribute("llm.tool_definition_count",
                        tools == null ? 0L : tools.size())) {
            ExecutionArtifactStore.Operation artifact = null;
            try {
                ChatRequest request = new ChatRequest(model, messages, false, tools);
                String json = objectMapper.writeValueAsString(request);
                scope.attribute("llm.request_chars", json.length());
                artifact = tracing.artifacts().beginOperation(
                        "llm", model, json);

                Request httpRequest = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .post(RequestBody.create(json, JSON))
                        .build();

                Call call = httpClient.newCall(httpRequest);
                activeCalls.add(call);
                try (Response response = executeInterruptibly(call)) {
                    scope.attribute("http.status_code", response.code());
                    String body = response.body().string();
                    if (!response.isSuccessful()) {
                        artifact.failure(body);
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
                        scope.attribute("gen_ai.usage.input_tokens",
                                chatResponse.usage.promptTokens);
                        scope.attribute("gen_ai.usage.output_tokens",
                                chatResponse.usage.completionTokens);
                    }
                    artifact.success(body);
                    tracing.metrics().recordLlm(
                            model,
                            "SUCCESS",
                            scope.elapsedMillis(),
                            choice.message.inputTokens,
                            choice.message.outputTokens);
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
                } finally {
                    activeCalls.remove(call);
                }
            } catch (IOException | RuntimeException error) {
                scope.fail(error);
                if (artifact != null) artifact.failure(error.toString());
                tracing.metrics().recordLlm(
                        model, "FAILED", scope.elapsedMillis(), 0L, 0L);
                logger.atError()
                        .addKeyValue("event", "llm.chat.failed")
                        .addKeyValue("model", model)
                        .addKeyValue("error_type",
                                error.getClass().getSimpleName())
                        .setCause(error)
                        .log("LLM 调用失败");
                throw error;
            } finally {
                if (artifact != null) artifact.close();
            }
        }
    }

    /** 流式输出 assistant 文本，同时重组 ReAct 所需的完整消息和碎片化 Tool Call。 */
    public Message chatRawStreaming(
            List<Message> messages,
            List<Map<String, Object>> tools,
            Consumer<String> onTextDelta) throws IOException {
        Consumer<String> deltaConsumer =
                onTextDelta == null ? ignored -> { } : onTextDelta;
        try (TraceScope scope = tracing.startClient("llm.chat")
                .attribute("gen_ai.operation.name", "chat")
                .attribute("gen_ai.provider.name", "deepseek")
                .attribute("gen_ai.request.model", model)
                .attribute("llm.streaming", true)
                .attribute("llm.message_count", messages.size())
                .attribute("llm.tool_definition_count",
                        tools == null ? 0L : tools.size())) {
            ExecutionArtifactStore.Operation artifact = null;
            try {
                ChatRequest request =
                        new ChatRequest(model, messages, true, tools);
                String json = objectMapper.writeValueAsString(request);
                scope.attribute("llm.request_chars", json.length());
                artifact = tracing.artifacts().beginOperation(
                        "llm-stream", model, json);

                Request httpRequest = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream")
                        .post(RequestBody.create(json, JSON))
                        .build();

                Call call = httpClient.newCall(httpRequest);
                activeCalls.add(call);
                try (Response response = executeInterruptibly(call)) {
                    scope.attribute("http.status_code", response.code());
                    if (!response.isSuccessful()) {
                        String body = response.body().string();
                        artifact.failure(body);
                        throw new IOException(
                                "LLM API error " + response.code()
                                        + ", response_chars=" + body.length());
                    }

                    StreamAccumulator accumulator =
                            new StreamAccumulator(deltaConsumer);
                    try (BufferedReader reader =
                                 new BufferedReader(
                                         response.body().charStream())) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (Thread.currentThread().isInterrupted()) {
                                call.cancel();
                                throw new InterruptedIOException(
                                        "LLM streaming cancelled");
                            }
                            if (line.isBlank() || line.startsWith(":")) {
                                continue;
                            }
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).stripLeading();
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            if (!data.isBlank()) {
                                accumulator.accept(
                                        objectMapper.readValue(
                                                data, StreamChunk.class));
                            }
                        }
                    }

                    Message result = accumulator.toMessage();
                    if (result.content == null
                            && (result.toolCalls == null
                            || result.toolCalls.isEmpty())) {
                        throw new IOException(
                                "LLM stream completed without content");
                    }
                    scope.attribute(
                                    "gen_ai.finish_reason",
                                    result.finishReason)
                            .attribute(
                                    "gen_ai.usage.input_tokens",
                                    result.inputTokens)
                            .attribute(
                                    "gen_ai.usage.output_tokens",
                                    result.outputTokens);
                    artifact.success(objectMapper.writeValueAsString(result));
                    tracing.metrics().recordLlm(
                            model,
                            "SUCCESS",
                            scope.elapsedMillis(),
                            result.inputTokens,
                            result.outputTokens);
                    logger.atDebug()
                            .addKeyValue("event", "llm.chat.completed")
                            .addKeyValue("model", model)
                            .addKeyValue("streaming", true)
                            .addKeyValue(
                                    "finish_reason", result.finishReason)
                            .addKeyValue(
                                    "input_tokens", result.inputTokens)
                            .addKeyValue(
                                    "output_tokens", result.outputTokens)
                            .addKeyValue(
                                    "duration_ms", scope.elapsedMillis())
                            .log("LLM 流式调用完成");
                    return result;
                } finally {
                    activeCalls.remove(call);
                }
            } catch (IOException | RuntimeException error) {
                scope.fail(error);
                if (artifact != null) artifact.failure(error.toString());
                tracing.metrics().recordLlm(
                        model, "FAILED", scope.elapsedMillis(), 0L, 0L);
                logger.atError()
                        .addKeyValue("event", "llm.chat.failed")
                        .addKeyValue("model", model)
                        .addKeyValue("streaming", true)
                        .addKeyValue(
                                "error_type",
                                error.getClass().getSimpleName())
                        .setCause(error)
                        .log("LLM 流式调用失败");
                throw error;
            } finally {
                if (artifact != null) artifact.close();
            }
        }
    }

    /**
     * 取消此客户端持有的全部请求，包括阻塞在
     * {@link BufferedReader#readLine()} 的 SSE 响应体。
     *
     * <p>应用同一时刻只允许一个前台任务，因此取消共享客户端会同时覆盖普通 Agent
     * 和当前 Plan 的所有并行 Worker。</p>
     */
    public void cancelActiveRequests() {
        activeCalls.forEach(Call::cancel);
    }

    /**
     * 在保留同步调用接口的同时使用 OkHttp 可取消异步请求；Agent 被中断时会立即取消
     * 底层连接，而不是等待 HTTP 超时。
     */
    private static Response executeInterruptibly(Call call)
            throws IOException {
        CompletableFuture<Response> response = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException error) {
                response.completeExceptionally(error);
            }

            @Override
            public void onResponse(Call ignored, Response value) {
                if (!response.complete(value)) {
                    value.close();
                }
            }
        });
        try {
            return response.get();
        } catch (InterruptedException interrupted) {
            call.cancel();
            Thread.currentThread().interrupt();
            InterruptedIOException cancelled =
                    new InterruptedIOException("LLM request cancelled");
            cancelled.initCause(interrupted);
            if (!response.completeExceptionally(cancelled)) {
                /* 响应可能在 get() 感知中断前刚好完成，但调用方已无法接收，必须主动关闭。 */
                try {
                    Response orphan = response.getNow(null);
                    if (orphan != null) {
                        orphan.close();
                    }
                } catch (RuntimeException ignored) {
                    // Future 中只有异常，没有需要关闭的响应体。
                }
            }
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        }
    }

    // ---------------- 数据模型 ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        public String role;       // 消息角色：system / user / assistant / tool
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

        @JsonProperty("stream_options")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Map<String, Object> streamOptions;

        ChatRequest(String model, List<Message> messages, boolean stream) {
            this(model, messages, stream, null);
        }

        ChatRequest(String model, List<Message> messages, boolean stream,
                    List<Map<String, Object>> tools) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.tools = tools;
            this.streamOptions = stream
                    ? Map.of("include_usage", true) : null;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StreamChunk {
        public List<StreamChoice> choices;
        public Usage usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StreamChoice {
        public Delta delta;
        @JsonProperty("finish_reason")
        public String finishReason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Delta {
        public String role;
        public String content;
        @JsonProperty("tool_calls")
        public List<DeltaToolCall> toolCalls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DeltaToolCall {
        public int index;
        public String id;
        public String type;
        public Function function;
    }

    static final class StreamAccumulator {
        private final Consumer<String> onTextDelta;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, MutableToolCall> toolCalls =
                new TreeMap<>();
        private String finishReason;
        private Usage usage;

        StreamAccumulator(Consumer<String> onTextDelta) {
            this.onTextDelta = onTextDelta;
        }

        void accept(StreamChunk chunk) {
            if (chunk.usage != null) {
                usage = chunk.usage;
            }
            if (chunk.choices == null) {
                return;
            }
            for (StreamChoice choice : chunk.choices) {
                if (choice.finishReason != null) {
                    finishReason = choice.finishReason;
                }
                if (choice.delta == null) {
                    continue;
                }
                if (choice.delta.content != null
                        && !choice.delta.content.isEmpty()) {
                    content.append(choice.delta.content);
                    onTextDelta.accept(choice.delta.content);
                }
                if (choice.delta.toolCalls == null) {
                    continue;
                }
                for (DeltaToolCall delta : choice.delta.toolCalls) {
                    MutableToolCall mutable = toolCalls.computeIfAbsent(
                            delta.index, ignored -> new MutableToolCall());
                    if (delta.id != null) {
                        mutable.id.append(delta.id);
                    }
                    if (delta.type != null) {
                        mutable.type = delta.type;
                    }
                    if (delta.function != null) {
                        if (delta.function.name != null) {
                            mutable.name.append(delta.function.name);
                        }
                        if (delta.function.arguments != null) {
                            mutable.arguments.append(
                                    delta.function.arguments);
                        }
                    }
                }
            }
        }

        Message toMessage() {
            Message message = new Message(
                    "assistant",
                    content.isEmpty() ? null : content.toString());
            if (!toolCalls.isEmpty()) {
                List<ToolCall> completed = new ArrayList<>();
                toolCalls.values().forEach(mutable -> {
                    ToolCall call = new ToolCall();
                    call.id = mutable.id.toString();
                    call.type = mutable.type;
                    call.function = new Function();
                    call.function.name = mutable.name.toString();
                    call.function.arguments = mutable.arguments.toString();
                    completed.add(call);
                });
                message.toolCalls = completed;
            }
            message.finishReason = finishReason;
            if (usage != null) {
                message.inputTokens = usage.promptTokens;
                message.outputTokens = usage.completionTokens;
                message.totalTokens = usage.totalTokens;
            }
            return message;
        }
    }

    private static final class MutableToolCall {
        private final StringBuilder id = new StringBuilder();
        private String type = "function";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
