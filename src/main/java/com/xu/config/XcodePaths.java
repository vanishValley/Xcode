package com.xu.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 统一管理 Xcode 所有路径 —— 替代原来散落在 SessionStore / KnowledgeStore / PlanStore / Main
 * 四处的路径计算逻辑。
 *
 * 两层目录:
 *   ~/.xcode/                    ← 用户级: 跨所有项目的数据、配置、日志
 *     projects/<name@hash>/      ← 项目数据: 会话、记忆、checkpoint、日志(按项目隔离)
 *       logs/                    ← 当前项目的运行日志和滚动归档
 *     skills/                    ← 用户级 Skill 覆盖
 *
 *  <project>/.xcode/            ← 项目级: 可入 git 的团队共享配置
 *     skills/                    ← 项目级 Skill 覆盖(最高优先级)
 *
 * 项目 key: SHA-256(标准化路径) 截取前 16 位十六进制(64-bit)
 *   替代旧 String.hashCode() 的 32 位弱哈希,消除碰撞风险。
 */
public class XcodePaths {

    private final Path projectRoot;     // 标准化后的项目根
    private final String projectKey;    // SHA-256 前 8 位
    private final String projectName;   // 项目根目录名

    public XcodePaths(Path projectRoot) {
        // ① 标准化: 去 ../、统一分隔符、解析符号链接
        this.projectRoot = normalize(projectRoot);
        this.projectName = this.projectRoot.getFileName().toString();
        this.projectKey = sha256hex(this.projectRoot.toString()).substring(0, 16);  // 64-bit, 消除碰撞
    }

    // ── 用户级目录 ──

    /** ~/.xcode/ — 跨所有项目 */
    public Path userDir() {
        return Path.of(System.getProperty("user.home"), ".xcode");
    }

    /** ~/.xcode/projects/<name@key>/ — 项目运行时数据 */
    public Path projectDataDir() {
        return userDir().resolve("projects").resolve(projectName + "@" + projectKey);
    }

    /** ~/.xcode/skills/ — 用户级 Skill 覆盖 */
    public Path userSkillsDir() {
        return userDir().resolve("skills");
    }

    /**
     * 当前项目的日志目录。
     *
     * <p>日志仍位于用户级 {@code ~/.xcode} 下，不污染源码仓库；
     * 但跟随 projectDataDir 按“项目名 + 路径哈希”隔离。</p>
     */
    public Path logsDir() {
        return projectDataDir().resolve("logs");
    }

    // ── 项目级目录 ──

    /** <project>/.xcode/ — 项目级配置(Skill 覆盖 / 本地配置, 可入 git) */
    public Path projectConfigDir() {
        return projectRoot.resolve(".xcode");
    }

    /** <project>/.xcode/skills/ — 项目级 Skill 覆盖(最高优先级) */
    public Path projectSkillsDir() {
        return projectConfigDir().resolve("skills");
    }

    // ── 初始化 ──

    /**
     * 首次进入一个项目目录时自动创建 .xcode/skills/ 骨架。
     * 不报错、不交互 —— 目录空着无所谓, 以后有覆盖文件就生效。
     */
    public void initProjectIfNeeded() {
        try {
            Path skillsDir = projectSkillsDir();
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
                logger().atInfo()
                        .addKeyValue("event", "project.config.initialized")
                        .addKeyValue("path", projectConfigDir())
                        .log("项目配置目录初始化完成");
            }
        } catch (IOException e) {
            logger().atError()
                    .addKeyValue("event", "project.config.initialize_failed")
                    .addKeyValue("path", projectConfigDir())
                    .setCause(e)
                    .log("项目配置目录初始化失败");
        }
    }

    // ── 辅助 ──

    /**
     * 延迟创建 Logger。
     *
     * <p>Main 必须先通过 XcodePaths 算出项目日志目录，再初始化 Logback。
     * 如果这里使用 static final Logger，类加载时就会过早锁定日志文件路径。</p>
     */
    private static Logger logger() {
        return LoggerFactory.getLogger(XcodePaths.class);
    }

    /** 路径标准化: 去 ../、/..\\、统一分隔符、解析符号链接 */
    private static Path normalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            // toRealPath 失败(文件不存在 / 无权限) → 退回 normalize
            return p.toAbsolutePath().normalize();
        }
    }

    /** SHA-256 → 十六进制, 同一路径永远算出同一串 */
    private static String sha256hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JVM 都内置 SHA-256, 不应该走到这里
            throw new RuntimeException(e);
        }
    }

    // ── getter ──

    public Path projectRoot() { return projectRoot; }
    public String projectKey()  { return projectKey; }
    public String projectName() { return projectName; }
}
