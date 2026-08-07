package com.xu.skill;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将用户明确禁用的 Skill 保存到 {@code ~/.xcode/skills.json}。
 * 只记录禁用项，使后续新增的 Skill 默认保持启用。
 */
public class SkillStateStore {

    private static final Logger logger = LoggerFactory.getLogger(SkillStateStore.class);

    /** load_skill 工具执行时读取 disabled 列表用的 holder，Main 初始化后设。 */
    public static volatile Set<String> DISABLED_HOLDER = Set.of();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path file;
    private volatile Map<String, Boolean> disabled = new ConcurrentHashMap<>();

    public SkillStateStore(Path file) {
        this.file = file;
        load();
    }

    /** 该 skill 是否被禁用 */
    public boolean isDisabled(String name) {
        return disabled.containsKey(name);
    }

    public Set<String> disabledNames() {
        return Set.copyOf(disabled.keySet());
    }

    public void enable(String name) {
        disabled.remove(name);
        persist();
    }

    public void disable(String name) {
        disabled.put(name, true);
        persist();
    }

    // ── 持久化 ──

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(file)) { disabled = new ConcurrentHashMap<>(); return; }
        try {
            Map<String, Object> json = MAPPER.readValue(file.toFile(), Map.class);
            Object raw = json.get("disabled");
            if (raw instanceof List<?> list) {
                Map<String, Boolean> m = new ConcurrentHashMap<>();
                for (Object item : list) m.put(item.toString(), true);
                disabled = m;
            } else {
                disabled = new ConcurrentHashMap<>();
            }
        } catch (IOException e) {
            disabled = new ConcurrentHashMap<>();
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> json = Map.of("disabled", new ArrayList<>(disabled.keySet()));
            MAPPER.writeValue(file.toFile(), json);
        } catch (IOException e) {
            logger.error("持久化失败: {}", e.getMessage());
        }
    }
}
