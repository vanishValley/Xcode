package com.xu.skill;

import com.xu.config.XcodePaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 注册表 —— 三层扫描 + 同名覆盖 + 启用过滤 + 热重载。
 *
 * 三层优先级(低 → 高):
 *   ① builtin  jar 内置 classpath:skills/
 *   ② user     ~/.xcode/skills/
 *   ③ project  <项目>/.xcode/skills/
 *
 * 同 name 的 skill, 后加载的覆盖前面的(put 覆盖语义)。
 * 因此 project 覆盖 user 覆盖 builtin —— 和 Git config / ESLint 同一模式。
 *
 * Registry 只负责“发现和覆盖”，不负责决定何时使用 Skill。是否命中
 * web-access 由 Agent 根据 system prompt 中的轻量索引自行判断。
 *
 * reload() 全量重建 —— 不设缓存, 每次重扫就是最新状态。
 */
public class SkillRegistry {

    /** 最多启用多少 skill(防止索引段撑爆 system prompt) */
    private static final int MAX_ENABLED_SKILLS = 20;

    private final XcodePaths paths;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public SkillRegistry(XcodePaths paths) {
        this.paths = paths;
    }

    /**
     * 三层全量扫描, 清除旧的 Map 重建。
     *
     * 为什么加 synchronized:
     *   现在只启动时调一次, 确实没有并发。但后面做 /skill reload 时——
     *   用户敲命令的同时, Agent.run() 可能正在 buildSkillIndex() 遍历 skills Map。
     *   一个线程在 clear+putAll 重建 Map, 另一个线程在 forEach 遍历 ——
     *   遍历到一半 Map 被清空了 → ConcurrentModificationException。
     *
     *   LinkedHashMap 不是并发安全的, 且"先 clear 再逐个 put"不是原子操作。
     *   synchronized 把 reload() 和未来的所有读方法包在同一把锁上,
     *   保证 reload 期间不会有读者看到半成品 Map。
     *   (现在没有并发读者, 先写上, 不做过度优化)
     */
    public synchronized void reload() {
        skills.clear();
        warnings.clear();

        // 第一层: jar 内置
        loadBuiltin();
        // 第二层: ~/.xcode/skills/
        loadDirectory(paths.userSkillsDir(), Skill.Source.USER);
        // 第三层: <项目>/.xcode/skills/
        loadDirectory(paths.projectSkillsDir(), Skill.Source.PROJECT);
    }

    // ── 查询 ──

    /** 所有已发现的 skill(含禁用的) */
    public List<Skill> allSkills() {
        return List.copyOf(skills.values());
    }

    /** 仅返回启用的(默认全启用, 除非被 disabled 列表过滤掉) */
    public List<Skill> enabledSkills(Set<String> disabledNames) {
        return skills.values().stream()
                .filter(s -> !disabledNames.contains(s.name()))
                .sorted(Comparator.comparing(Skill::name))
                .limit(MAX_ENABLED_SKILLS)
                .toList();
    }

    public Optional<Skill> findSkill(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<String> warnings() { return List.copyOf(warnings); }

    // ── 内部: 三层扫描 ──

    /**
     * 从 classpath:skills/index.txt 清单加载。
     *
     * 不能用 Files.walk 去扫描 resources：开发态资源是目录，打成 JAR 后却不再是
     * 普通文件系统目录。显式索引能让 IDE 和可执行 JAR 使用同一套加载逻辑。
     */
    private void loadBuiltin() {
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream indexStream =
                     classLoader.getResourceAsStream("skills/index.txt")) {
            if (indexStream == null) {
                warnings.add("内置 Skill 清单不存在: classpath:skills/index.txt");
                return;
            }
            String index = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : index.split("\\R")) {
                String dirName = line.strip();
                if (dirName.isEmpty() || dirName.startsWith("#")) continue;
                if (!dirName.matches("[a-z0-9][a-z0-9-]*")) {
                    warnings.add("忽略非法内置 Skill 目录名: " + dirName);
                    continue;
                }

                String resourcePath = "skills/" + dirName + "/SKILL.md";
                try (InputStream skillStream =
                             classLoader.getResourceAsStream(resourcePath)) {
                    if (skillStream == null) {
                        warnings.add("内置 Skill 资源不存在: classpath:" + resourcePath);
                        continue;
                    }
                    String raw = new String(
                            skillStream.readAllBytes(), StandardCharsets.UTF_8);
                    Skill skill = parseSkillText(
                            dirName,
                            raw,
                            Skill.Source.BUILTIN,
                            Path.of("classpath-skills", dirName, "SKILL.md"),
                            null);
                    if (skill != null) skills.put(skill.name(), skill);
                }
            }
        } catch (Exception e) {
            warnings.add("内置 Skill 扫描失败: " + e.getMessage());
        }
    }

    /** 扫描一个目录下的所有 skill 子目录 */
    private void loadDirectory(Path dir, Skill.Source source) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.filter(Files::isDirectory).sorted().toList()) {
                Path skillMd = entry.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;
                Skill skill = parseSkill(entry, skillMd, source);
                if (skill != null) skills.put(skill.name(), skill);
            }
        } catch (IOException e) {
            warnings.add("扫描 " + dir + " 失败: " + e.getMessage());
        }
    }

    // ── 解析单个 Skill ──

    /** 读 SKILL.md → 解析 frontmatter → 构造 Skill 对象 */
    private Skill parseSkill(Path entryDir, Path skillMdPath, Skill.Source source) {
        try {
            String raw = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            Path refDir = entryDir.resolve("references");
            if (!Files.isDirectory(refDir)) refDir = null;
            return parseSkillText(
                    entryDir.getFileName().toString(),
                    raw, source, skillMdPath, refDir);
        } catch (IOException e) {
            warnings.add("读取 " + skillMdPath + " 失败: " + e.getMessage());
            return null;
        }
    }

    private Skill parseSkillText(String defaultName,
                                 String raw,
                                 Skill.Source source,
                                 Path skillMdPath,
                                 Path refDir) {
        var result = SkillFrontmatterParser.parse(raw);
        warnings.addAll(result.warnings());

        Map<String, Object> fm = result.frontmatter();
        String name = stringVal(fm, "name", defaultName);
        String desc = stringVal(fm, "description", "");
        String version = stringVal(fm, "version", "");
        String author = stringVal(fm, "author", "");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) fm.getOrDefault("tags", List.of());

        return new Skill(name, desc, version, author, tags, source,
                result.body(), skillMdPath, refDir);
    }

    private static String stringVal(Map<String, Object> fm, String key, String defaultVal) {
        Object v = fm.get(key);
        return v == null ? defaultVal : v.toString();
    }
}
