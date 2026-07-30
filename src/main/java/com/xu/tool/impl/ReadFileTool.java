package com.xu.tool.impl;

import com.xu.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取项目中指定文件的内容。参数：path（文件路径，相对于项目根目录）";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "path", Map.of(
                        "type", "string",
                        "description", "要读取的文件路径，相对于项目根目录"
                )
        ));
        schema.put("required", java.util.List.of("path"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            return "错误：缺少 path 参数";
        }

        // 读文件，限制最大 100KB
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            return "文件不存在：" + path;
        }
        if (Files.size(filePath) > 100 * 1024) {
            return "文件过大，超过 100KB 限制";
        }

        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            return "读取失败：" + e.getMessage();
        }
    }
}
