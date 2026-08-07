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
 * 持久化 Plan 任务图的执行进度，用于进程重启后的安全恢复。
 *
 * <p>它与保存 LLM 对话的 SessionStore 相互独立：检查点只记录任务 ID、依赖、状态、
 * 结果和重规划次数，不注入模型上下文。数据通过显式映射和原子写入保存，并携带结构版本；
 * 计划确定完成后删除检查点，状态未知的任务则保留供人工核验。</p>
 */
public class PlanStore {

    private static final Logger logger = LoggerFactory.getLogger(PlanStore.class);

    /** 当前检查点结构版本；持久化字段不兼容时递增。 */
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

    // ────── 检查点数据模型 ──────

    /**
     * 一次 Plan 执行的完整快照。用户请求和重规划次数属于执行元数据，
     * 因此通过 record 与任务图组合，而不写入 ExecutionPlan。
     */
    public record Checkpoint(String userRequest, int replanCount, ExecutionPlan plan) {}

    // ────── 存 ──────

    /**
     * 原子重写完整检查点。失败时返回 {@code false}：可变 Worker 启动前必须失败关闭，
     * 终态更新则由调用方决定是否仅记录日志。
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
     * 加载检查点。
     * @return 无文件或内容损坏时返回 {@code null}
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
                     * 任务持久化为“已启动”后进程异常退出，其副作用状态未知，
                     * 因此恢复时必须失败关闭，不能静默重放。
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

    /** 解析持久化状态；返回 {@code null} 表示状态未知，调用方必须失败关闭。 */
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

    /** 在 Plan 确定完成或用户明确丢弃时删除检查点。 */
    public void delete() {
        try {
            Files.deleteIfExists(checkpointFile);
        } catch (IOException e) {
            logger.error("checkpoint 删除失败: {}", e.getMessage());
        }
    }
}
