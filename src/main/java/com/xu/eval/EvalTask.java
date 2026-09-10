package com.xu.eval;

import java.util.List;
import java.util.Map;

/** 一道可复现的题；reference 和 checks 只交给评测端，不放进 Agent 工作区。 */
public record EvalTask(String id, String family, String category, String difficulty,
                       String split, String prompt, Map<String, String> files,
                       Map<String, String> reference, List<String> editable,
                       Map<String, String> checks, String fault) {
    public EvalTask {
        if (id == null || !id.matches("[a-z0-9-]+") || files == null || files.isEmpty()
                || reference == null || editable == null || checks == null
                || !checks.keySet().containsAll(List.of("BehaviorCheck", "RegressionCheck"))) {
            throw new IllegalArgumentException("Invalid task: " + id);
        }
        fault = fault == null ? "none" : fault;
    }
    public String instructions() {
        return prompt + "\n只允许修改以下源文件：" + String.join(", ", editable)
                + "。保持公开 API 兼容，不新增依赖。工作区不包含 Git 历史。"
                + "\n使用 Java 17。可以运行 javac *.java 和 java VisibleCheck 检查可见用例。";
    }
}
