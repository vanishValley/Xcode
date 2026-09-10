package com.xu.eval;

import java.util.Map;

/** runtimeStatus 是宿主执行状态，status 是外部验收结论，二者不能混用。 */
public record EvalResult(String taskId, String family, String category, int trial,
                         String adapter, String profile, String model, String status,
                         String runtimeStatus, long durationMs, long gradingMs,
                         long inputTokens, long outputTokens, boolean usageComplete,
                         int llmCalls, int toolCalls, boolean faultTriggered,
                         Map<String, Boolean> checks, String evidenceDir, String error) {
    public boolean passed() { return "PASS".equals(status); }
    public boolean eligible() { return !"INFRA_ERROR".equals(status); }
}
