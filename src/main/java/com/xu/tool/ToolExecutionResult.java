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
}
