package com.xu.hitl;

import com.xu.tool.Tool;
import com.xu.tool.ToolExecutionResult;
import com.xu.tool.ToolRegistry;
import com.xu.util.CancellationToken;
import com.xu.observability.TraceScope;
import com.xu.observability.Tracing;

import java.util.Map;

/**
 * HITL 拦截层 —— 继承 ToolRegistry, 覆写 get() 返回包了审批逻辑的匿名子类。
 *
 * 不搞 AOP、不改 Agent、不改工具实现、不改调用链。
 * Agent 仍然调 tool.execute(), 但拿到的 Tool 是被偷偷替换过的——
 * execute() 里先走审批, 通过了才进原始逻辑。
 *
 * 审批委托给 HitlHandler：plain 与 TUI 可以使用不同实现，本类不变。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler handler;
    private final CancellationToken cancellation;
    private final Tracing tracing;
    private volatile boolean enabled = false;

    public HitlToolRegistry(HitlHandler handler) {
        this(handler, new CancellationToken(), Tracing.noop());
    }

    public HitlToolRegistry(
            HitlHandler handler,
            CancellationToken cancellation) {
        this(handler, cancellation, Tracing.noop());
    }

    public HitlToolRegistry(
            HitlHandler handler,
            CancellationToken cancellation,
            Tracing tracing) {
        this.handler = handler;
        this.cancellation = cancellation == null
                ? new CancellationToken() : cancellation;
        this.tracing = tracing == null ? Tracing.noop() : tracing;
    }

    public void setEnabled(boolean e) { this.enabled = e; }
    public boolean isEnabled() { return enabled; }

    /** 清空"全部放行"列表: /clear 或 /hitl off 时调用 */
    public void clearApprovalState() {
        handler.clearSessionState();
    }

    @Override
    public Tool get(String name) {
        Tool original = super.get(name);
        if (original == null) return null;

        // 不需要审批 → 原样返回, 零开销
        if (!enabled || !ApprovalPolicy.requiresApproval(name)) {
            return original;
        }

        // 需要审批 → 包一层匿名子类, Agent 拿到的就是它
        return new Tool() {
            @Override public String name()          { return original.name(); }
            @Override public String description()   { return original.description(); }
            @Override public Map<String, Object> inputSchema() { return original.inputSchema(); }

            @Override
            public String execute(Map<String, Object> arguments) throws Exception {
                return executeObserved(arguments).content();
            }

            @Override
            public ToolExecutionResult executeObserved(
                    Map<String, Object> arguments) throws Exception {
                cancellation.throwIfCancellationRequested();
                ApprovalResult result;
                try (TraceScope scope = tracing.start("hitl.wait")
                        .attribute("tool.name", original.name())
                        .attribute("hitl.danger_level",
                                ApprovalPolicy.dangerLevel(original.name()))) {
                    result = handler.requestApproval(
                            original.name(), arguments);
                    scope.attribute("hitl.decision", result.type().name());
                }
                cancellation.throwIfCancellationRequested();

                if (result.isApproved()) {
                    cancellation.throwIfCancellationRequested();
                    return original.executeObserved(arguments);
                }
                if (result.type() == ApprovalResult.Type.SKIPPED) {
                    return ToolExecutionResult.failure(
                            "[HITL] 用户已跳过此步骤",
                            "HITL_SKIPPED");
                }
                // 用户明确拒绝。
                return ToolExecutionResult.failure(
                        "[HITL] 用户拒绝: " + result.reason(),
                        "HITL_REJECTED");
            }
        };
    }

}
