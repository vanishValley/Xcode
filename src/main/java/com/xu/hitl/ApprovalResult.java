package com.xu.hitl;

/**
 * 审批结果 —— Handler 返回, ToolRegistry 根据 type 决定是否执行原始工具。
 *
 * reason: 只在 REJECTED 时有意义, 会作为 tool result 回灌给 LLM,
 *         让 LLM 知道"为什么被拒", 以便重新规划。
 */
public record ApprovalResult(Type type, String reason) {
    public enum Type {
        /** 批准本次执行 */
        APPROVED,
        /** 拒绝, 不执行, reason 回灌给 LLM */
        REJECTED,
        /** 本会话此工具全放行(后续自动通过, 不再弹窗) */
        APPROVED_ALL,
        /** 跳过本步骤(Agent 以为执行了但实际没做) */
        SKIPPED
    }

    public boolean isApproved() { return type == Type.APPROVED || type == Type.APPROVED_ALL; }
    public boolean isRejected() { return type == Type.REJECTED || type == Type.SKIPPED; }
}
