package com.xu.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reviewer 的输出,从 LLM 返回的 JSON 解析而来。
 *
 * 防御策略:LLM 输出不稳定,解析层做容错——
 *   - issues/suggestions 字段可能是数组、可能是单字符串、可能不存在
 *   - JSON 解析失败 → 默认通过(审察者自己挂了,不阻塞业务)
 *   - approved 字段缺失 → 默认通过(同上)
 */
public record ReviewResult(boolean approved, List<String> issues, List<String> suggestions) {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 从 LLM 返回文本解析,内置两层 fallback。 */
    public static ReviewResult parse(String llmOutput) {
        try {
            // 只去 markdown 包裹,不做数组提取(Reviewer 输出的是 JSON 对象,不是数组)
            String json = llmOutput.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                start = (start == -1) ? 3 : start + 1;
                int end = json.lastIndexOf("```");
                json = (end > start) ? json.substring(start, end) : json.substring(start);
                json = json.strip();
            }
            JsonNode root = mapper.readTree(json);
            boolean approved = root.path("approved").asBoolean(true);  // 缺失 → 默认通过
            List<String> issues = extractStringList(root, "issues");
            List<String> suggestions = extractStringList(root, "suggestions");
            return new ReviewResult(approved, issues, suggestions);
        } catch (Exception e) {
            // JSON 解析彻底失败 → 宽容放行
            return new ReviewResult(true,
                    List.of("审查者返回了无法解析的格式,已放行: " + llmOutput), List.of());
        }
    }

    /** 容错提取:字段可能是 JSON 数组 / 单字符串 / 不存在。 */
    private static List<String> extractStringList(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (node.isArray()) {
            List<String> list = new ArrayList<>();
            node.forEach(item -> list.add(item.asText("")));
            return list;
        }
        if (node.isTextual()) return List.of(node.asText());  // 单字符串 → 包成列表
        return List.of();
    }
}
