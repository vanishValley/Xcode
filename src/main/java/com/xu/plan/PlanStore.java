package com.xu.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.util.FileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Plan 执行进度持久化 —— Plan 模式中途进程退出后，重启能从断点续跑。
 *
 * 存的是什么？（关键区别，别和 SessionStore 搞混）
 *   SessionStore 存 List&lt;Message&gt; —— LLM 的"对话流"，恢复后灌回 LLM。
 *   PlanStore   存 ExecutionPlan  —— 任务图的"执行进度"（每个 task 的
 *                id/依赖/状态/结果），恢复后靠 getReadyTasks 从断点继续调度，
 *                不进任何 LLM 上下文。
 *
 * 生命周期：checkpoint 是"在途标记"不是"归档"。
 *   plan 执行期间反复覆盖同一个文件；plan 全部完成(isAllComplete)后必须删除，
 *   否则下次启动会误判"有未完成计划"。
 *
 * 设计要点：
 *   1. 手动序列化(Map)而非 Jackson 注解 —— Task/ExecutionPlan 是充血领域模型
 *      (final 字段 + 大量计算型 getter)，自动序列化会侵入领域类、吐出派生垃圾。
 *   2. 原子写(复用 FileUtils) —— checkpoint 永不半张，崩溃时读到的永远一致。
 *   3. schema 版本号 —— 存 "version"，为将来 Task 加字段留向后兼容余地。
 *   4. 容错 —— load 失败/损坏当"无 checkpoint"处理，绝不让程序起不来。
 */
public class PlanStore {

    private static final Logger logger = LoggerFactory.getLogger(PlanStore.class);

    /** 当前 checkpoint schema 版本；字段结构变化时递增 */
    private static final int SCHEMA_VERSION = 1;

    private static final String FILE_NAME = "plan_checkpoint.json";
    public static final String INTERRUPTED_RESULT_PREFIX =
            "[INTERRUPTED] ";

    private final Path checkpointFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param projectDataDir 项目数据目录（和 session.jsonl 同目录，
     *                       用 sessionStore.projectDir(projectPath) 取）
     */
    public PlanStore(Path projectDataDir) {
        this.checkpointFile = projectDataDir.resolve(FILE_NAME);
    }

    // ────── Checkpoint 数据模型 ──────

    /**
     * 一次 plan 执行的完整快照 = 任务图 + 会话元数据。
     * 用 record 组合 ExecutionPlan（复用图、不污染它 —— userRequest/replanCount
     * 是"执行会话"元数据，不属于任务图本身）。
     */
    public record Checkpoint(String userRequest, int replanCount, ExecutionPlan plan) {}

    // ────── 存 ──────

    /**
     * 保存 checkpoint（全量原子重写）。
     * 失败只记录日志、不抛出，并通过返回值交给调用方决定策略。变更型 Worker
     * 启动前必须检查该返回值并 fail closed；终态更新则可以尽力保存。
     */
    public boolean save(Checkpoint cp) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", SCHEMA_VERSION);
            root.put("userRequest", cp.userRequest());
            root.put("replanCount", cp.replanCount());

            List<Map<String, Object>> taskList = new ArrayList<>();
            for (Task t : cp.plan().getAllTasks()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("description", t.getDescription());
                m.put("dependencies", t.getDependencies());
                m.put("status", t.getStatus().name());   // enum → 字符串
                m.put("result", t.getResult());
                taskList.add(m);
            }
            root.put("tasks", taskList);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            FileUtils.atomicWrite(checkpointFile, json);
            return true;
        } catch (IOException e) {
            logger.error("checkpoint 保存失败: {}", e.getMessage());
            return false;
        }
    }

    // ────── 读 ──────

    /**
     * 加载 checkpoint。
     * @return Checkpoint；无文件 / 解析失败 / 损坏都返回 null（当作"无 checkpoint"）
     */
    @SuppressWarnings("unchecked")
    public Checkpoint load() {
        if (!Files.exists(checkpointFile)) return null;
        try {
            Map<String, Object> root = mapper.readValue(checkpointFile.toFile(), Map.class);

            String userRequest = String.valueOf(root.getOrDefault("userRequest", ""));
            int replanCount = root.get("replanCount") instanceof Number n ? n.intValue() : 0;

            ExecutionPlan plan = new ExecutionPlan();
            List<Map<String, Object>> taskList =
                    (List<Map<String, Object>>) root.getOrDefault("tasks", List.of());
            for (Map<String, Object> m : taskList) {
                String id = (String) m.get("id");
                String description = (String) m.get("description");
                List<String> deps = (List<String>) m.getOrDefault("dependencies", List.of());

                Task t = new Task(id, description, deps);
                Task.Status status = parseStatus(m.get("status"));
                String result = m.get("result") != null
                        ? String.valueOf(m.get("result")) : "";
                if (status == null) {
                    status = Task.Status.FAILED;
                    result = INTERRUPTED_RESULT_PREFIX
                            + "检查点中的任务状态无法识别；"
                            + "为避免重复副作用，未自动重试。";
                } else if (status == Task.Status.IN_PROGRESS) {
                    /*
                     * The process died after this task was durably marked as
                     * started. Its side effects are unknown, so silently
                     * replaying it would be unsafe.
                     */
                    status = Task.Status.FAILED;
                    result = INTERRUPTED_RESULT_PREFIX
                            + "上次运行在此步骤中断，未自动重试。";
                }
                t.setStatus(status);
                t.setResult(result);
                plan.addTask(t);
            }
            return new Checkpoint(userRequest, replanCount, plan);
        } catch (Exception e) {
            logger.error(
                    "checkpoint 损坏, 忽略: {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** Parses a persisted status; null means unknown and must fail closed. */
    private Task.Status parseStatus(Object raw) {
        try {
            return Task.Status.valueOf(String.valueOf(raw));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasInterruptedTasks(Checkpoint checkpoint) {
        return checkpoint != null
                && checkpoint.plan().getAllTasks().stream()
                        .anyMatch(task ->
                                task.getStatus() == Task.Status.FAILED
                                && task.getResult() != null
                                && task.getResult().startsWith(
                                        INTERRUPTED_RESULT_PREFIX));
    }

    // ────── 删 / 查 ──────

    public boolean exists() {
        return Files.exists(checkpointFile);
    }

    /** 删除 checkpoint（plan 完成或用户丢弃时调） */
    public void delete() {
        try {
            Files.deleteIfExists(checkpointFile);
        } catch (IOException e) {
            logger.error("checkpoint 删除失败: {}", e.getMessage());
        }
    }
}
