package com.xu.hitl;

import java.util.Map;
import java.util.Set;

/**
 * 审批处理器接口 —— 隔离交互方式和审批逻辑。
 *
 * plain 模式使用 TerminalHitlHandler，TUI 使用基于事件和 Future 的
 * TuiHitlHandler；HitlToolRegistry 不关心具体交互方式。
 */
public interface HitlHandler {
    /**
     * 请求人工审批。由 HitlToolRegistry 在工具执行前调用。
     *
     * @param toolName  工具名
     * @param arguments LLM 生成的参数
     * @return 审批决策(批准/拒绝/全部放行/跳过)
     */
    ApprovalResult requestApproval(String toolName, Map<String, Object> arguments);

    default void clearSessionState() {
    }

    default Set<String> approvedAllTools() {
        return Set.of();
    }
}
