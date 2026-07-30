package com.xu.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 测试
 *
 * 覆盖：注册 → 查找 → toOpenAiTools JSON 格式验证 → 空注册表
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void shouldRegisterAndGetTool() {
        Tool dummy = dummyTool("test_tool", "测试工具");
        registry.register(dummy);

        assertEquals(dummy, registry.get("test_tool"));
        assertTrue(registry.names().contains("test_tool"));
        assertFalse(registry.isEmpty());
    }

    @Test
    void shouldReturnNullForUnknownTool() {
        assertNull(registry.get("不存在"));
    }

    @Test
    void shouldProduceValidOpenAiToolsFormat() {
        registry.register(dummyTool("read_file", "读取文件"));
        registry.register(dummyTool("write_file", "写入文件"));

        List<Map<String, Object>> tools = registry.toOpenAiTools();

        // 应该返回 2 个工具定义
        assertEquals(2, tools.size());

        // 第一个工具的结构
        Map<String, Object> first = tools.get(0);
        assertEquals("function", first.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) first.get("function");
        assertEquals("read_file", function.get("name"));
        assertEquals("读取文件", function.get("description"));
        assertNotNull(function.get("parameters"));
    }

    @Test
    void shouldBeEmptyByDefault() {
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.names().size());
        assertTrue(registry.toOpenAiTools().isEmpty());
    }

    // ---- 辅助：创建一个最小实现的 Tool ----

    private static Tool dummyTool(String name, String description) {
        return new Tool() {
            @Override public String name() { return name; }

            @Override public String description() { return description; }

            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override public String execute(Map<String, Object> arguments) {
                return "dummy result";
            }
        };
    }
}
