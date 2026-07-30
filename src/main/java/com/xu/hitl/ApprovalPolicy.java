package com.xu.hitl;

import java.util.Set;

/**
 * 危险工具判定 —— 不涉及审批流程本身，只负责"这个工具需不需要审批"和"风险等级是什么"。
 *
 * 判定规则:
 *   ① 对文件/系统有修改能力的工具: write_file / execute_command / create_project / revert_turn
 *   ② 所有 MCP 外部工具(mcp__ 前缀): MCP server 提供的工具能力不可预测，默认需审批
 *   ③ 其余纯读取工具(read_file / list_dir / glob_files / web_search / web_fetch): 不放行
 *
 * 没有配置文件、没有数据库: 工具集小且稳定，代码即文档。
 * 工具集频繁变动时再抽配置文件——YAGNI 原则(你不会需要它)。
 */
public final class ApprovalPolicy {

    /** 需要审批的内置工具 */
    private static final Set<String> DANGEROUS = Set.of(
            "write_file",
            "execute_command",
            "create_project",
            "revert_turn"
    );

    private ApprovalPolicy() {}

    /** 这个工具是否需要审批 */
    public static boolean requiresApproval(String toolName) {
        if (toolName == null) return false;
        return DANGEROUS.contains(toolName) || toolName.startsWith("mcp__");
    }

    /** 审批弹窗展示用的风险等级描述 */
    public static String dangerLevel(String toolName) {
        if (toolName == null) return "未知";
        if ("execute_command".equals(toolName)) return "高危 — 将在本机执行 Shell 命令";
        if ("revert_turn".equals(toolName)) return "高危 — 将批量回写工作区文件";
        if (toolName.startsWith("mcp__")) return "MCP — 将调用外部 MCP 工具";
        return "中危 — 将写入或覆盖文件内容";
    }
}
