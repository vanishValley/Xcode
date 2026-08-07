package com.xu.tool;

/**
 * 一次工具执行的统一结果。
 *
 * @param success   工具是否正常完成
 * @param content   回灌给 LLM 的工具结果或简化错误信息
 * @param errorType 失败类型；成功时为 {@code null}
 * @param exitCode  命令类工具的退出码；其他工具为 {@code null}
 * @param timedOut  是否因超时结束
 */
public record ToolExecutionResult(
        boolean success,
        String content,
        String errorType,
        Integer exitCode,
        boolean timedOut) {

    /** 保留原来的三参数构造方式，避免现有自定义工具受到影响。 */
    public ToolExecutionResult(
            boolean success,
            String content,
            String errorType) {
        this(success, content, errorType, null, false);
    }

    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(
                true, content, null, null, false);
    }

    public static ToolExecutionResult failure(
            String content, String errorType) {
        return new ToolExecutionResult(
                false, content, errorType, null, false);
    }

    public static ToolExecutionResult command(
            String content,
            int exitCode,
            boolean timedOut) {
        if (timedOut) {
            return new ToolExecutionResult(
                    false,
                    content,
                    "COMMAND_TIMEOUT",
                    exitCode,
                    true);
        }
        if (exitCode != 0) {
            return new ToolExecutionResult(
                    false,
                    content,
                    "COMMAND_EXIT_NON_ZERO",
                    exitCode,
                    false);
        }
        return new ToolExecutionResult(
                true, content, null, exitCode, false);
    }

    /**
     * 将旧式字符串结果转换为结构化结果。新工具应实现 {@code executeObserved}；
     * 此适配器负责识别旧工具的明显失败，避免界面将失败误报为成功。
     */
    public static ToolExecutionResult fromLegacyText(String content) {
        String value = content == null ? "" : content;
        String stripped = value.stripLeading();
        boolean failure =
                stripped.startsWith("错误:")
                        || stripped.startsWith("错误：")
                        || stripped.startsWith("文件不存在")
                        || stripped.startsWith("文件过大")
                        || stripped.startsWith("读取失败")
                        || stripped.startsWith("写入失败")
                        || stripped.startsWith("目录不存在")
                        || stripped.startsWith("路径不是目录")
                        || stripped.startsWith("Skill '")
                        && (stripped.contains("不存在")
                        || stripped.contains("已被禁用"))
                        || stripped.startsWith("MCP_TOOL_ERROR")
                        || hasJsonStatus(stripped, "error")
                        || hasJsonStatus(stripped, "blocked");
        return failure
                ? failure(value, "TOOL_REPORTED_FAILURE")
                : success(value);
    }

    private static boolean hasJsonStatus(String value, String status) {
        return value.matches(
                "(?s).*\"status\"\\s*:\\s*\"" + status + "\".*");
    }
}
