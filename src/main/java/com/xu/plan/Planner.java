package com.xu.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;

import java.util.List;
import java.util.Map;

/**
 * 将自然语言任务转换成结构化 ExecutionPlan。规划阶段只调用模型，不暴露工具，
 * 避免尚未形成依赖图时提前执行操作。
 */
public class Planner {

    /**
     * 规划 Prompt：限定模型只输出带 ID、描述和依赖的 JSON 数组，
     * 并控制步骤数量及依赖引用范围。
     */
    public static final String PLANNER_SYSTEM_PROMPT = """
            你是一个任务规划专家。你的职责是把用户的复杂需求拆解成清晰的、可执行的步骤列表。

            规则：
            1. 每个步骤用自然语言描述要做什么（不是具体代码，是目标）
            2. 如果有先后依赖关系，用 dependencies 字段声明
            3. 可以并行执行的步骤不要加多余的依赖
            4. 步骤数控制在能清楚表达的最小数量，一般不超过 8 个

            **你必须只输出以下格式的 JSON 数组，不要加任何其他文字：**

            ```json
            [
              {
                "id": "task_0",
                "description": "步骤描述",
                "dependencies": []
              },
              {
                "id": "task_1",
                "description": "这个步骤依赖 task_0 完成才能开始",
                "dependencies": ["task_0"]
              }
            ]
            ```

            注意：
            - id 必须从 task_0 开始递增
            - dependencies 里的 id 必须在前面已经定义过
            - 没有依赖就传空数组 []
            - 用中文描述
            - 如果描述中需要用到双引号，请用单引号代替，或者用 \\" 转义""";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public Planner(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 把用户需求拆成执行计划。
     *
     * @param userRequest 用户的原始需求，如"创建一个 Spring Boot demo 项目"
     * @return 构建好的 ExecutionPlan
     */
    public ExecutionPlan plan(String userRequest) throws Exception {
        // 步骤 1：构造不带工具定义的规划请求。
        // 只有 system + user 两条消息，不带工具——规划不需要调工具
        List<Message> messages = List.of(
                new Message("system", PLANNER_SYSTEM_PROMPT),
                new Message("user", "请为以下任务生成执行计划：\n\n" + userRequest)
        );

        // 步骤 2：请求模型生成计划。
        Message reply = llmClient.chatRaw(messages, null);
        String rawJson = reply.content;

        if (rawJson == null || rawJson.isBlank()) {
            throw new RuntimeException("LLM 未返回有效的规划结果");
        }

        // 步骤 3：去除模型可能添加的 Markdown 代码块等包装。
        String json = extractJson(rawJson);

        // 步骤 4：解析任务数组。
        List<Map<String, Object>> taskList;
        try {
            taskList = objectMapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "规划结果解析失败。LLM 返回:\n" + rawJson + "\n\n清洗后:\n" + json, e);
        }

        // 步骤 5：构建任务图。
        ExecutionPlan plan = new ExecutionPlan();
        for (Map<String, Object> item : taskList) {
            String id = (String) item.get("id");
            String description = (String) item.get("description");

            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) item.getOrDefault("dependencies", List.of());

            Task task = new Task(id, description, deps);
            plan.addTask(task);
        }

        // 步骤 6：拒绝空计划。
        if (plan.size() == 0) {
            throw new RuntimeException("规划结果为空");
        }

        return plan;
    }

    /**
     * 从模型回复中定位 JSON 数组，移除 Markdown 包装，并修复字符串值内常见的
     * 未转义双引号。
     */
    public static String extractJson(String raw) {
        String s = raw.strip();

        // 1. 移除 Markdown 代码块标记。
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            start = (start == -1) ? 3 : start + 1;
            int end = s.lastIndexOf("```");
            s = (end > start) ? s.substring(start, end) : s.substring(start);
        }

        // 2. 定位 JSON 数组边界。
        int arrayStart = s.indexOf('[');
        int arrayEnd = s.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            s = s.substring(arrayStart, arrayEnd + 1);
        }

        // 3. 修复字符串值内未转义的双引号。
        // LLM 输出形如 "description": "包含打印"Hello"的代码"
        // 其中 "Hello" 的引号没转义，导致解析崩溃。
        // 这里的思路：逐字符扫描，跟踪当前是否在 JSON 字符串 value 内部；
        // 遇到一个未转义的 " → 如果前一个字符不是 JSON 控制字符（逗号、冒号、方括号等），
        // 就把它当作 value 内部的字面引号，在前面补 \
        s = repairInnerQuotes(s);

        return s.strip();
    }

    /**
     * 修复 JSON 字符串 value 内部未转义的双引号。
     *
     * 扫描规则：
     *   - 遇到 \"（已转义）→ 跳过，继续
     *   - 遇到 " → 切换"是否在字符串内部"状态
     *   - 在字符串内部遇到 " → 这是 value 内部的字面引号，需要转义
     *
     * 判断"这个引号是 value 的结束还是 value 内的字面引号"的关键：
     *   紧跟它后面的非空字符如果是 , } ] :  → 这是 JSON 结构控制符，说明引号是 value 的结束
     *   否则 → 这很可能是 value 内的字面文本引号
     */
    private static String repairInnerQuotes(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            // 已转义的引号 → 原样保留，跳过
            if (c == '\\' && i + 1 < json.length() && json.charAt(i + 1) == '"') {
                result.append(c);
                result.append(json.charAt(i + 1));
                i++;  // 跳过了 \" 两个字符
                continue;
            }

            if (c == '"') {
                if (!inString) {
                    // 进入字符串
                    inString = true;
                    result.append(c);
                } else {
                    // 遇到了一个 (未转义的) 引号，而我们在字符串内部
                    // 判断它是 value 的结束标记还是字面文本引号
                    char nextNonSpace = findNextNonSpace(json, i + 1);

                    if (nextNonSpace == ',' || nextNonSpace == '}'
                            || nextNonSpace == ']' || nextNonSpace == ':'
                            || nextNonSpace == '\0') {
                        // 后面紧跟 JSON 控制符 → 这是 value 的真正结束
                        inString = false;
                        result.append(c);
                    } else {
                        // 后面不是 JSON 控制符 → value 内部的字面引号，需要转义
                        result.append("\\\"");
                    }
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private static char findNextNonSpace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return c;
            }
        }
        return '\0';  // 到达字符串末尾
    }
}
