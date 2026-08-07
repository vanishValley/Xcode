package com.xu.hitl;

import com.xu.ui.SafeDisplay;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;

/**
 * plain CLI 的人工审批实现。
 *
 * <p>应用应注入共享的 Scanner，确保标准输入只有一个读取者；无参构造器仅为兼容旧 API 保留。</p>
 */
public class TerminalHitlHandler implements HitlHandler {

    private final Set<String> approvedAll = ConcurrentHashMap.newKeySet();
    private final Scanner scanner;
    private final PrintWriter writer;

    public TerminalHitlHandler() {
        this(new Scanner(System.in), new PrintWriter(System.out, true));
    }

    public TerminalHitlHandler(Scanner scanner) {
        this(scanner, new PrintWriter(System.out, true));
    }

    public TerminalHitlHandler(Scanner scanner, PrintWriter writer) {
        this.scanner = scanner;
        this.writer = writer;
    }

    @Override
    public synchronized ApprovalResult requestApproval(
            String toolName,
            Map<String, Object> arguments) {
        if (approvedAll.contains(toolName)) {
            return new ApprovalResult(ApprovalResult.Type.APPROVED, null);
        }

        printBox(toolName, SafeDisplay.arguments(arguments));
        while (scanner.hasNextLine()) {
            writer.print("approval> ");
            writer.flush();
            String input = scanner.nextLine().strip().toLowerCase();
            switch (input) {
                case "y", "yes":
                    return new ApprovalResult(
                            ApprovalResult.Type.APPROVED, null);
                case "a", "all":
                    approvedAll.add(toolName);
                    writer.println("  本会话后续 " + toolName + " 将自动放行。");
                    return new ApprovalResult(
                            ApprovalResult.Type.APPROVED_ALL, null);
                case "s", "skip":
                    return new ApprovalResult(
                            ApprovalResult.Type.SKIPPED, null);
                case "", "n", "no":
                    writer.print("拒绝原因（可留空）> ");
                    writer.flush();
                    String reason = scanner.hasNextLine()
                            ? scanner.nextLine().strip() : "";
                    return new ApprovalResult(
                            ApprovalResult.Type.REJECTED,
                            reason.isBlank() ? "用户拒绝" : reason);
                default:
                    writer.println("请输入 y、a、s 或 n；直接回车表示拒绝。");
            }
        }
        return new ApprovalResult(
                ApprovalResult.Type.REJECTED, "输入已关闭");
    }

    public void clearApprovedAll() {
        approvedAll.clear();
    }

    @Override
    public void clearSessionState() {
        clearApprovedAll();
    }

    @Override
    public Set<String> approvedAllTools() {
        return Set.copyOf(approvedAll);
    }

    private void printBox(
            String toolName,
            Map<String, Object> safeArguments) {
        writer.println();
        writer.println("--------------------------------------------------");
        writer.println("HITL 审批  |  " + ApprovalPolicy.dangerLevel(toolName));
        String taskLabel = MDC.get("task_id");
        if (taskLabel != null
                && !taskLabel.isBlank()
                && !"main".equals(taskLabel)) {
            writer.println("步骤: " + SafeDisplay.text(taskLabel));
        }
        writer.println("工具: " + toolName);
        safeArguments.forEach(
                (key, value) -> writer.println("  " + key + ": " + value));
        writer.println(
                "[y] 允许一次  [a] 本会话始终允许此工具  [s] 跳过  [n] 拒绝");
    }
}
