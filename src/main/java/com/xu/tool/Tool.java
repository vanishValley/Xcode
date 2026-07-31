package com.xu.tool;

import java.util.Map;

/**
 * 工具接口：每个工具有一个名称、一个 JSON Schema 描述、
 * 以及一个 execute 方法真正干活。
 *
 * LLM 靠 name + description 知道该不该用这个工具。
 * LLM 靠 inputSchema 知道该传什么参数。
 */
public interface Tool {

    /** 工具唯一名称，LLM 调用时用它 */
    String name();

    /** 工具描述，LLM 靠它判断是否应该调用 */
    String description();

    /**
     * 参数的 JSON Schema，告诉 LLM 该传什么参数、什么类型
     * 例如 read_file 的 schema: {"path": {"type": "string"}}
     */
    Map<String, Object> inputSchema();

    /** 真正执行工具，参数从 LLM 的 tool_call 解析出来 */
    String execute(Map<String, Object> arguments) throws Exception;

    /**
     * 返回可观测的结构化执行结果。
     *
     * <p>普通工具沿用默认实现即可；需要暴露退出码或超时状态的工具可以覆写。</p>
     */
    default ToolExecutionResult executeObserved(
            Map<String, Object> arguments) throws Exception {
        return ToolExecutionResult.fromLegacyText(execute(arguments));
    }
}
