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
 * 仅持久化 disabled 列表({name → true})。
 *
 * 为什么不是 enabled 列表?
 *   新增 Skill 时默认为启用 —— 如果持久化 enabled 列表, 新 skill 会被遗漏。
 *   只记录"用户主动关了哪些" —— 没记录的都默认启用。
 *
 * 文件: ~/.xcode/skills.json  →  {"disabled": ["foo", "bar"]}
 * 文件不存在 → 所有 skill 默认启用。
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
