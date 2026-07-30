package com.xu.hitl;

import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 审批交互实现 —— System.out 弹窗 + Scanner 读用户输入。
 *
 * 设计要点:
 *   ① synchronized: Plan 并行执行时两个子 Agent 可能同时走到审批点,
 *      不加锁两个审批弹窗会 stdout 交叠, 用户无法正常输入。
 *   ② 全部放行状态: ConcurrentHashMap.newKeySet() 存"本会话永久放行"的工具名。
 *      后续同工具调用直接通过, 不再弹窗。/clear 时清空。
 *   ③ 拒绝原因: 用户可选填, 回灌给 LLM 让它知道为什么被拦, 以便调整策略。
 */
public class TerminalHitlHandler implements HitlHandler {

    private final Set<String> approvedAll = ConcurrentHashMap.newKeySet();
    private final Scanner scanner = new Scanner(System.in);

    @Override
    public synchronized ApprovalResult requestApproval(String toolName,
                                                        Map<String, Object> arguments) {
        // 该工具已"全部放行" → 直接过
        if (approvedAll.contains(toolName)) {
            return new ApprovalResult(ApprovalResult.Type.APPROVED, null);
        }

        // 打印审批弹窗
        printBox(toolName, arguments);

        // 读用户选择
        System.out.print("> ");
        String input = scanner.nextLine().strip().toLowerCase();

        return switch (input) {
            case "", "y" -> new ApprovalResult(ApprovalResult.Type.APPROVED, null);

            case "a" -> {
                approvedAll.add(toolName);
                System.out.println("  → 已放行: 本会话后续调用 " + toolName + " 将自动通过");
                yield new ApprovalResult(ApprovalResult.Type.APPROVED_ALL, null);
            }

            case "s" -> {
                System.out.println("  → 已跳过");
                yield new ApprovalResult(ApprovalResult.Type.SKIPPED, null);
            }

            default -> {
                System.out.print("  拒绝原因（可选，回车跳过）: ");
                String reason = scanner.nextLine().strip();
                String msg = reason.isBlank() ? "用户拒绝" : reason;
                System.out.println("  → 已拒绝: " + msg);
                yield new ApprovalResult(ApprovalResult.Type.REJECTED, msg);
            }
        };
    }

    /** 清空"全部放行"列表: /clear 和 /hitl off 时调用 */
    public void clearApprovedAll() {
        approvedAll.clear();
    }

    /** 供系统 prompt 展示: 当前放行了哪些工具 */
    public Set<String> approvedAllTools() {
        return Set.copyOf(approvedAll);
    }

    // ── 弹窗 ──

    private void printBox(String toolName, Map<String, Object> args) {
        String level = ApprovalPolicy.dangerLevel(toolName);
        String line = "═".repeat(50);

        System.out.println("\n" + line);
        System.out.println("  ⚠️  HITL 审批");
        System.out.println("  工具: " + toolName);
        System.out.println("  等级: " + level);
        System.out.println("  参数:");
        for (var e : args.entrySet()) {
            String val = formatValue(e.getValue());
            System.out.printf("    %s: %s%n", e.getKey(), val);
        }
        System.out.println("  [y]批准  [n]拒绝  [a]全部放行  [s]跳过");
    }

    /** 格式化参数值: 字符串截断到 200 字, 其他类型原样 toString */
    private static String formatValue(Object v) {
        if (v == null) return "null";
        String s = v.toString();
        if (s.length() > 200) s = s.substring(0, 200) + "... (共 " + v.toString().length() + " 字符)";
        return s;
    }
}
