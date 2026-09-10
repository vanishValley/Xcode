package com.xu.team;

import com.xu.llm.LlmClient;
import com.xu.util.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 包括 MemoryManager 压缩请求在内，统一记账。真实 HTTP 仍使用共享客户端。 */
final class TeamModelClient extends LlmClient {
    private final LlmClient delegate;
    private final TeamBudget budget;
    private final TeamConfig config;
    private final TeamRuntime runtime;
    private final String agentId;
    private final CancellationToken token;
    TeamModelClient(LlmClient delegate, TeamBudget budget, TeamConfig config,
                    TeamRuntime runtime, String agentId, CancellationToken token) {
        super("", "team-delegating-client");
        this.delegate = delegate; this.budget = budget; this.config = config;
        this.runtime = runtime; this.agentId = agentId; this.token = token;
    }
    @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools) throws IOException {
        if (token.isCancelled() || !token.isReusable() || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Team cancelled");
        // 按完整序列化字节估算，包含工具参数，避免只统计正文造成漏算。
        long estimate = (TeamJson.write(messages).getBytes(StandardCharsets.UTF_8).length
                + TeamJson.write(tools).getBytes(StandardCharsets.UTF_8).length + 1L) / 2;
        if (estimate + config.outputTokens() > 120_000)
            throw new TeamException("CONTEXT_TOO_LARGE", "压缩后上下文仍过大，请拆分任务");
        long reservation = budget.reserve(estimate);
        String requestId = UUID.randomUUID().toString();
        Message reply = null;
        try {
            java.util.Set<String> messageIds = new java.util.LinkedHashSet<>();
            java.util.regex.Pattern ids = java.util.regex.Pattern.compile("msg-[0-9a-f-]{36}");
            for (Message message : messages) {
                java.util.regex.Matcher matcher = ids.matcher(message.content == null ? "" : message.content);
                while (matcher.find()) messageIds.add(matcher.group());
            }
            runtime.requestRecorded(agentId, requestId, "REQUEST_ATTEMPTED", TeamJson.write(Map.of(
                    "referencedMessageIds", messageIds, "estimatedInputTokens", estimate)));
            reply = delegate.chatRaw(messages, tools, config.outputTokens());
            return reply;
        } finally {
            budget.settle(reservation, reply == null ? 0 : reply.inputTokens + reply.outputTokens);
            runtime.requestRecorded(agentId, requestId, reply == null ? "REQUEST_FAILED" : "REQUEST_COMPLETED");
        }
    }
}
