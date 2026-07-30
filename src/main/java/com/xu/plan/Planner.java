package com.xu.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.llm.LlmClient;
import com.xu.llm.LlmClient.Message;

import java.util.List;
import java.util.Map;

/**
 * 规划器 —— 把用户的自然语言任务拆成结构化执行计划。
 *
 * 工作原理：
 *   1. 用特制的 system prompt 告诉 LLM "你是规划专家"
 *   2. 把用户任务发过去，要求 LLM 输出 JSON 格式的步骤列表
 *   3. 解析 JSON，构建 ExecutionPlan
 *
 * 为什么需要专门的 Planner 而不是复用 Agent？
 *   规划是一个"纯思考"过程——不需要调工具，只需要 LLM 的推理能力。
 *   用 Agent 反而可能让 LLM 在规划阶段就去调 read_file，浪费 token 和时间。
 */
public class Planner {

    /**
     * 规划用的 system prompt。
     *
     * 关键设计：
     *   - 明确角色：你是规划专家，不是执行者
     *   - 明确输出格式：纯 JSON 数组，每个元素有 id/description/dependencies
     *   - 给出例子：LLM 看到例子比看到抽象说明更准确
     *   - 约束：id 格式 "task_N"，description 用中文且具体，依赖只能引用已存在的 task id
     *   - 边界：默认不超过 8 个步骤；简单任务 1-3 个即可
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
        // Step 1: 构造规划请求
        // 只有 system + user 两条消息，不带工具——规划不需要调工具
        List<Message> messages = List.of(
                new Message("system", PLANNER_SYSTEM_PROMPT),
                new Message("user", "请为以下任务生成执行计划：\n\n" + userRequest)
        );

        // Step 2: 调 LLM（不带 tools）
        Message reply = llmClient.chatRaw(messages, null);
        String rawJson = reply.content;

        if (rawJson == null || rawJson.isBlank()) {
            throw new RuntimeException("LLM 未返回有效的规划结果");
        }

        // Step 3: 清洗 JSON——LLM 经常在 JSON 外面包 markdown 代码块
        String json = extractJson(rawJson);

        // Step 4: 解析 JSON
        List<Map<String, Object>> taskList;
        try {
            taskList = objectMapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "规划结果解析失败。LLM 返回:\n" + rawJson + "\n\n清洗后:\n" + json, e);
        }

        // Step 5: 构建 ExecutionPlan
        ExecutionPlan plan = new ExecutionPlan();
        for (Map<String, Object> item : taskList) {
            String id = (String) item.get("id");
            String description = (String) item.get("description");

            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) item.getOrDefault("dependencies", List.of());

            Task task = new Task(id, description, deps);
            plan.addTask(task);
        }

        // Step 6: 基本校验
        if (plan.size() == 0) {
            throw new RuntimeException("规划结果为空");
        }

        return plan;
    }

    /**
     * 从 LLM 原始回复中提取纯 JSON 数组，并修复常见格式错误。
     *
     * LLM 常见毛病：
     *   1. JSON 外面包 markdown 代码块 ```json ... ```
     *   2. JSON 前面有"以下是执行计划"之类的废话
     *   3. 字符串内包含未转义的双引号（中文引号尤其常见）
     *
     * 修复策略：
     *   先清洗外层（去 markdown、定位数组起止），
     *   再尝试修复 value 内未转义的双引号。
     */
    public static String extractJson(String raw) {
        String s = raw.strip();

        // ---- 1. 去掉 markdown 代码块标记 ----
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            start = (start == -1) ? 3 : start + 1;
            int end = s.lastIndexOf("```");
            s = (end > start) ? s.substring(start, end) : s.substring(start);
        }

        // ---- 2. 定位 JSON 数组的 [ ... ] ----
        int arrayStart = s.indexOf('[');
        int arrayEnd = s.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            s = s.substring(arrayStart, arrayEnd + 1);
        }

        // ---- 3. 修复 value 内未转义的双引号 ----
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
