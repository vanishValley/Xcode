package com.xu.agent;

import com.xu.llm.LlmClient.Message;
import com.xu.llm.LlmClient.ToolCall;
import java.util.List;

/** 单次执行的协作边界。默认实现不改变普通 Agent / Plan 的行为。 */
public interface AgentRunHooks {
    AgentRunHooks NONE = new AgentRunHooks() {};
    default int maxTurns() { return 20; }
    default String instructions() { return ""; }
    /** 仅由 Agent 执行线程调用；不能由发送方直接修改 history。 */
    default List<Message> receive() { return List.of(); }
    default void beforeRequest() {}
    default void beforeTool(ToolCall call) {}
    default boolean pauseRequested() { return false; }
    default String pauseReason() { return "等待主 Agent 回复"; }
    /** 返回非空说明时，候选最终答案被拒绝，继续下一轮。 */
    default String finishBlocker() { return ""; }
    default boolean preserveHistoryOnFailure() { return false; }
}
