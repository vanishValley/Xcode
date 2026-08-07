package com.xu.tool;

import java.util.*;

/**
 * 工具注册表：管理所有可用的工具。
 *
 * 两个核心职责：
 *  1. 把工具列表转换成 OpenAI 的 tools JSON 格式发给 LLM
 *  2. 根据 LLM 返回的工具名找到对应 Tool 执行
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 注册一个工具。
     *
     * <p>MCP 懒加载后注册表会在 Agent 运行期间发生写入，因此所有读写都使用
     * 同一把对象锁，保证 LinkedHashMap 的结构和内存可见性。</p>
     */
    public synchronized void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * 原子注册一批工具。
     *
     * <p>其他线程只能看到注册前或注册后的完整集合，不会看到 tools/list
     * 只发布了一半的中间状态。慢速 MCP 握手不在本锁内执行。</p>
     */
    public synchronized void registerAll(
            Collection<? extends Tool> newTools) {
        for (Tool tool : newTools) {
            tools.put(tool.name(), tool);
        }
    }

    /**
     * 注册 load_skill 工具。
     * Skill 正文直接作为 tool result 返回，当前 ReAct 轮的下一次模型调用即可使用。
     *
     * 不能把正文延迟到下一次用户输入再注入，否则当前联网任务已经失去 Skill 指导。
     * “索引常驻 prompt、正文按需加载”也比把所有 Skill 全塞进 system prompt 更省 token。
     */
    public void registerLoadSkillTool(com.xu.skill.SkillRegistry skillRegistry) {
        register(new Tool() {
            @Override
            public String name() { return "load_skill"; }

            @Override
            public String description() {
                return "按需加载 Skill 的完整指引手册。传入 Skill 名称(kebab-case, 如 web-access), "
                        + "立即返回该 Skill 的完整 Markdown 正文（上限 8KB）。";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "Skill 的 kebab-case 名称, 如 web-access"
                                )
                        ),
                        "required", List.of("name")
                );
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                String name = (String) arguments.get("name");
                if (name == null || name.isBlank()) return "错误: 缺少 Skill 名称";

                var disabled = com.xu.skill.SkillStateStore.DISABLED_HOLDER;
                if (disabled != null && disabled.contains(name)) {
                    return "Skill '" + name + "' 已被禁用, 可用 /skill on " + name + " 启用。";
                }

                var skill = skillRegistry.findSkill(name);
                if (skill.isEmpty()) {
                    return "Skill '" + name + "' 不存在。可用 /skills 查看已安装的 Skill。";
                }

                String body = skill.get().body();
                // 防止单个 Skill 过大，挤占工具结果和后续推理的上下文窗口。
                if (body.length() > 8 * 1024) {
                    body = body.substring(0, 8 * 1024)
                            + "\n\n...[已截断, 全文请查看 SKILL.md]";
                }
                return "## 已加载 Skill: " + name + "\n\n" + body;
            }
        });
    }

    /** 根据名称找工具 */
    public synchronized Tool get(String name) {
        return tools.get(name);
    }

    /** 所有已注册的工具名；返回快照，避免把 Map 的实时视图暴露给调用方。 */
    public synchronized Set<String> names() {
        return new LinkedHashSet<>(tools.keySet());
    }

    /** 是否为空 */
    public synchronized boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 把所有工具转成 OpenAI Chat Completions 的 tools 参数格式。
     * 这个 JSON 会跟在 messages 后面发给 LLM，告诉它"你可以用这些工具"。
     */
    public List<Map<String, Object>> toOpenAiTools() {
        List<Tool> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(tools.values());
        }

        // Tool 元数据转换不占用注册表锁，动态注册只在复制快照时短暂等待。
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : snapshot) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("function", Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.inputSchema()
            ));
            result.add(entry);
        }
        return result;
    }


}
