package com.xu.hitl;

import java.util.Map;

/**
 * 审批处理器接口 —— 隔离交互方式和审批逻辑。
 *
 * 现在: TerminalHitlHandler (System.out + Scanner)
 * 以后有 TUI 时可以换成弹窗实现，HitlToolRegistry 一行不改。
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
}
