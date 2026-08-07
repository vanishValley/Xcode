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
 * 负责 Skill 的发现、同名覆盖、启用过滤和热重载。
 *
 * <p>加载优先级为内置、用户级、项目级，后者覆盖前者。注册表不判断任务应使用哪个
 * Skill，只向 Agent 提供轻量索引和按名称查询能力。</p>
 */
public class SkillRegistry {

    /** 限制注入 Prompt 的 Skill 数量，避免索引占用过多上下文。 */
    private static final int MAX_ENABLED_SKILLS = 20;

    private final XcodePaths paths;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public SkillRegistry(XcodePaths paths) {
        this.paths = paths;
    }

    /**
     * 全量重建三层注册表。同步锁避免热重载与并发查询同时访问非线程安全的 LinkedHashMap。
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
