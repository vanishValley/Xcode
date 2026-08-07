package com.xu.ui;

import com.xu.hitl.ApprovalPolicy;
import com.xu.hitl.ApprovalResult;
import com.xu.hitl.HitlHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;

/** 将同步工具审批桥接到异步终端事件循环。 */
public final class TuiHitlHandler implements HitlHandler {

    private final UiEventSink events;
    private final AtomicLong requestIds = new AtomicLong();
    private final Object stateLock = new Object();
    private final Set<String> approvedAll = new HashSet<>();
    private final Map<CompletableFuture<ApprovalResult>, String> pending =
            new HashMap<>();
    private boolean accepting = true;

    public TuiHitlHandler(UiEventSink events) {
        this.events = events;
    }

    @Override
    public ApprovalResult requestApproval(
            String toolName,
            Map<String, Object> arguments) {
        CompletableFuture<ApprovalResult> response = new CompletableFuture<>();
        synchronized (stateLock) {
            if (!accepting || Thread.currentThread().isInterrupted()) {
                return rejected("操作已取消");
            }
            if (approvedAll.contains(toolName)) {
                return new ApprovalResult(
                        ApprovalResult.Type.APPROVED, null);
            }
            pending.put(response, toolName);
        }
        events.emit(new UiEvent.ApprovalRequested(
                requestIds.incrementAndGet(),
                MDC.get("task_id"),
                toolName,
                ApprovalPolicy.dangerLevel(toolName),
                SafeDisplay.arguments(arguments),
                response));
        try {
            ApprovalResult result = response.get();
            if (result.type() == ApprovalResult.Type.APPROVED_ALL) {
                synchronized (stateLock) {
                    approvedAll.add(toolName);
                }
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ApprovalResult rejection = rejected("审批被中断");
            response.complete(rejection);
            return rejection;
        } catch (ExecutionException failed) {
            return rejected("审批通道已关闭");
        } finally {
            synchronized (stateLock) {
                pending.remove(response);
            }
        }
    }

    @Override
    public void clearSessionState() {
        synchronized (stateLock) {
            approvedAll.clear();
        }
    }

    @Override
    public Set<String> approvedAllTools() {
        synchronized (stateLock) {
            return Set.copyOf(approvedAll);
        }
    }

    /** 上一次取消流程结束后，开始接受新的前台任务。 */
    public void beginRun() {
        synchronized (stateLock) {
            accepting = true;
        }
    }

    /** 收到 Ctrl+C、EOF 或终端关闭时，解除所有审批等待。 */
    public void cancelPending(String reason) {
        ApprovalResult rejection = rejected(reason);
        synchronized (stateLock) {
            accepting = false;
            pending.keySet().forEach(
                    future -> future.complete(rejection));
            pending.clear();
        }
    }

    /**
     * 完成当前审批；选择“本会话始终允许”时，也放行队列中同工具的请求，
     * 避免并行 Plan Worker 重复询问。
     */
    public boolean completeApproval(
            UiEvent.ApprovalRequested request,
            ApprovalResult result) {
        List<CompletableFuture<ApprovalResult>> release =
                new ArrayList<>();
        synchronized (stateLock) {
            String rawToolName = pending.get(request.response());
            if (rawToolName == null
                    || !request.response().complete(result)) {
                return false;
            }
            pending.remove(request.response());
            if (result.type() == ApprovalResult.Type.APPROVED_ALL) {
                approvedAll.add(rawToolName);
                pending.forEach((future, tool) -> {
                    if (rawToolName.equals(tool)) {
                        release.add(future);
                    }
                });
                release.forEach(pending::remove);
            }
        }
        ApprovalResult approved = new ApprovalResult(
                ApprovalResult.Type.APPROVED, null);
        release.forEach(future -> future.complete(approved));
        return true;
    }

    private static ApprovalResult rejected(String reason) {
        return new ApprovalResult(
                ApprovalResult.Type.REJECTED,
                reason == null || reason.isBlank()
                        ? "操作已取消" : reason);
    }
}
