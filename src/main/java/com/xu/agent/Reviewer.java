package com.xu.agent;

import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;
import com.xu.tool.ToolExecutionResult;
import com.xu.tool.ToolExecutor;
import com.xu.tool.ToolRegistry;
import com.xu.tool.impl.GlobFilesTool;
import com.xu.tool.impl.ListDirTool;
import com.xu.tool.impl.ReadFileTool;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码审查者 —— 只读工具 + 轻量 ReAct 循环(最多 3 轮)，亲自验证 Worker 的产物。
 *
 * 和 Worker 的区别:
 *   - 只有只读工具(read_file / list_dir / glob_files)，不能写文件、不能执行命令
 *   - 最多 3 轮验证，不做多轮执行
 *   - LLM 调用失败或 JSON 解析失败 → 宽容放行(不因审查者故障阻塞流程)
 */
public class Reviewer {

    private static final Logger logger = LoggerFactory.getLogger(Reviewer.class);
    private static final int MAX_REVIEW_TURNS = 3;

    private static final String SYSTEM_PROMPT = """
            你是代码审查专家。Worker 刚完成了一个子任务，请亲自验证产物是否满足要求。

            你可以使用 read_file / list_dir / glob_files 工具读取项目文件来验证。
            最多 %d 轮验证后，输出 JSON（不要用 markdown 代码块包裹）：

            {"approved": true, "issues": [], "suggestions": []}

            字段说明：
            - approved: 是否通过
            - issues: 未完成或做错的地方（必须修复），如果不通过必须列出所有问题
            - suggestions: 做得不够好但不会导致失败的改进建议（可选）

            规则：
            - 如果通过，issues 为空数组
            - 如果不通过，issues 必须列出每一个具体问题
            - Worker 的修改应该有可观测的产物（新文件、代码变更、依赖变化），若未观察到任何产物则认为不通过
            """.formatted(MAX_REVIEW_TURNS);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final Tracing tracing;
    private final CancellationToken cancellation;

    public Reviewer(LlmClient llmClient) {
        this(llmClient, Tracing.noop());
    }

    public Reviewer(LlmClient llmClient, Tracing tracing) {
        this(llmClient, tracing, new CancellationToken());
    }

    public Reviewer(
            LlmClient llmClient,
            Tracing tracing,
            CancellationToken cancellation) {
        this.llmClient = llmClient;
        this.tracing = tracing;
        this.cancellation = cancellation == null
                ? new CancellationToken() : cancellation;
        // 只注册只读工具
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.register(new ReadFileTool());
        this.toolRegistry.register(new ListDirTool());
        this.toolRegistry.register(new GlobFilesTool());
        this.toolExecutor = new ToolExecutor(
                toolRegistry,
                tracing,
                UiEventSink.noop(),
                "review",
                this.cancellation);
    }

    /**
     * 审查一个子任务的执行结果。
     *
     * @param userRequest  用户原始需求
     * @param taskDesc     当前子任务描述
     * @param workerResult Worker 自述的执行结果
     * @return 审查结果
     */
    public ReviewResult review(String userRequest, String taskDesc, String workerResult) {
        // review Span 覆盖“LLM 审查 + 只读工具验证”的完整过程。
        // 只记录文本长度，不把用户需求和 Worker 结果正文写入链路属性。
        try (TraceScope reviewScope = tracing.start("review")
                .attribute("review.task_description_chars", taskDesc.length())
                .attribute("review.worker_result_chars", workerResult.length())) {
            List<Message> history = new ArrayList<>();
            history.add(new Message("system", SYSTEM_PROMPT));

            String userMessage = "【用户原始需求】\n" + userRequest + "\n\n"
                    + "【当前子任务描述】\n" + taskDesc + "\n\n"
                    + "【Worker 自述的执行结果】\n" + workerResult + "\n\n"
                    + "请验证产物，最多 " + MAX_REVIEW_TURNS
                    + " 轮后输出 JSON 结论。";
            history.add(new Message("user", userMessage));

            // 轻量 ReAct 循环
            for (int turn = 0; turn < MAX_REVIEW_TURNS; turn++) {
                ensureActive();
                List<Map<String, Object>> tools = toolRegistry.isEmpty()
                        ? null : toolRegistry.toOpenAiTools();

                Message reply;
                try {
                    reply = llmClient.chatRaw(history, tools);
                } catch (Exception e) {
                    if (isCancellation(e)) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException(
                                "Review cancelled");
                    }
                    // LLM 调用失败 → 宽容放行
                    reviewScope.event("review.llm_failure_tolerated");
                    reviewScope.attribute("review.outcome", "TOLERATED");
                    logger.warn("LLM 调用失败, 宽容放行: {}",
                            e.getClass().getSimpleName());
                    return new ReviewResult(true,
                            List.of("审查者 LLM 调用异常,已自动放行"),
                            List.of());
                }

                // 没有工具调用 → 拿到最终结论
                if (reply.toolCalls == null || reply.toolCalls.isEmpty()) {
                    history.add(reply);
                    ReviewResult result = ReviewResult.parse(reply.content);
                    reviewScope.attribute(
                                    "review.approved", result.approved())
                            .attribute(
                                    "review.issue_count",
                                    result.issues().size())
                            .attribute("review.outcome",
                                    result.approved()
                                            ? "APPROVED"
                                            : "REJECTED");
                    logger.debug(
                            "审查结果: approved={}, issue_count={}, suggestion_count={}",
                            result.approved(),
                            result.issues().size(),
                            result.suggestions().size());
                    return result;
                }

                // 有工具调用 → 执行并回灌
                history.add(reply);
                for (LlmClient.ToolCall tc : reply.toolCalls) {
                    ensureActive();
                    ToolExecutionResult execution = toolExecutor.execute(tc);
                    Message toolMsg =
                            new Message("tool", execution.content());
                    toolMsg.toolCallId = tc.id;
                    history.add(toolMsg);
                }
            }

            // 兜底:跑满 3 轮还没给出结论 → 直接拿最后一条 assistant 消息解析
            String lastContent = "";
            for (int i = history.size() - 1; i >= 0; i--) {
                Message message = history.get(i);
                if ("assistant".equals(message.role)
                        && message.content != null) {
                    lastContent = message.content;
                    break;
                }
            }
            ReviewResult fallback = ReviewResult.parse(lastContent);
            reviewScope.attribute("review.approved", fallback.approved())
                    .attribute("review.outcome", "MAX_TURNS_FALLBACK");
            return fallback;
        }
    }

    private void ensureActive() {
        if (cancellation.isCancelled()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Review cancelled");
        }
    }

    private boolean isCancellation(Throwable error) {
        return cancellation.isCancelled()
                || error instanceof InterruptedException
                || error instanceof java.io.InterruptedIOException
                || error instanceof CancellationException
                || Thread.currentThread().isInterrupted();
    }
}
