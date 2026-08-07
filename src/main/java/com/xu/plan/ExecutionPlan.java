package com.xu.plan;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 执行计划 —— DAG 任务图的容器。
 *
 * 核心能力：
 *   1. 存储所有 Task
 *   2. getReadyTasks() —— 找出「所有依赖已完成、自身 PENDING」的 Task
 *      这是 DAG 拓扑执行的关键：不需要拓扑排序整张图，
 *      每轮只取"就绪"的 Task 执行即可
 *   3. 状态汇总：isAllComplete / isAllSuccess / getFailedTasks
 */
public class ExecutionPlan {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final List<String> taskOrder = new CopyOnWriteArrayList<>();

    /** 添加一个 Task */
    public void addTask(Task task) {
        if (!tasks.containsKey(task.getId())) {
            taskOrder.add(task.getId());
        }
        tasks.put(task.getId(), task);
    }

    /** 获取所有 Task（按添加顺序） */
    public List<Task> getAllTasks() {
        List<Task> ordered = new ArrayList<>(taskOrder.size());
        for (String id : taskOrder) {
            Task task = tasks.get(id);
            if (task != null) {
                ordered.add(task);
            }
        }
        return ordered;
    }

    /** 按 id 获取单个 Task */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /**
     * 获取所有"就绪可执行"的 Task。
     *
     * 条件：
     *   1. status == PENDING
     *   2. 它依赖的所有 task 状态都是 COMPLETED
     *
     * 例如：
     *   task_0 COMPLETED
     *   task_1 PENDING, deps=[task_0]  → 就绪 ✅
     *   task_2 PENDING, deps=[task_0]  → 就绪 ✅
     *   task_4 PENDING, deps=[task_2, task_3] → 不就绪 ❌（task_3 还没完成）
     */
    public List<Task> getReadyTasks() {
        List<Task> ready = new ArrayList<>();
        for (Task t : getAllTasks()) {
            if (t.getStatus() != Task.Status.PENDING) continue;

            boolean allDepsDone = true;
            for (String depId : t.getDependencies()) {
                Task dep = tasks.get(depId);
                // 依赖不存在或未完成 → 不就绪
                if (dep == null || dep.getStatus() != Task.Status.COMPLETED) {
                    allDepsDone = false;
                    break;
                }
            }
            if (allDepsDone) {
                ready.add(t);
            }
        }
        return ready;
    }

    /** 更新一个 Task 的状态和结果 */
    public void updateTask(String id, Task.Status status, String result) {
        Task t = tasks.get(id);
        if (t != null) {
            t.setStatus(status);
            t.setResult(result);
        }
    }

    /** 所有 Task 是否都处于终态（COMPLETED 或 FAILED） */
    public boolean isAllComplete() {
        return getAllTasks().stream()
                .allMatch(t -> t.getStatus() == Task.Status.COMPLETED
                            || t.getStatus() == Task.Status.FAILED);
    }

    /** 所有 Task 是否都已成功完成 */
    public boolean isAllSuccess() {
        return getAllTasks().stream()
                .allMatch(t -> t.getStatus() == Task.Status.COMPLETED);
    }

    /** 获取所有失败的 Task */
    public List<Task> getFailedTasks() {
        return getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.Status.FAILED)
                .collect(Collectors.toList());
    }

    /** 获取所有已完成的 Task */
    public List<Task> getCompletedTasks() {
        return getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.Status.COMPLETED)
                .collect(Collectors.toList());
    }

    /** 获取所有 PENDING 的 Task */
    public List<Task> getPendingTasks() {
        return getAllTasks().stream()
                .filter(t -> t.getStatus() == Task.Status.PENDING)
                .collect(Collectors.toList());
    }

    /** 移除所有 PENDING 状态的 Task（重规划前清理） */
    public void removePendingTasks() {
        getPendingTasks().forEach(task -> removeTask(task.getId()));
    }

    /** 移除指定 Task */
    public void removeTask(String id) {
        tasks.remove(id);
        taskOrder.remove(id);
    }

    /** 获取下一个可用的重规划 task id（如 task_r0） */
    public int nextReplanIndex() {
        int max = 0;
        for (String id : taskOrder) {
            // 从原版 task_N 和重规划版 task_rN 中找最大序号
            String numStr = id.replace("task_", "").replace("r", "");
            try {
                int n = Integer.parseInt(numStr);
                if (n >= max) max = n + 1;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    /**
     * 为重规划拼一段"当前已完成和失败的汇总"供 LLM 参考。
     */
    public String buildContextForReplan() {
        StringBuilder sb = new StringBuilder();

        List<Task> completed = getCompletedTasks();
        if (!completed.isEmpty()) {
            sb.append("已完成步骤（不许重做）：\n");
            for (Task t : completed) {
                sb.append("  ").append(t.getId()).append(": ")
                        .append(t.getDescription()).append("\n");
            }
        }

        List<Task> failed = getFailedTasks();
        if (!failed.isEmpty()) {
            sb.append("\n失败步骤：\n");
            for (Task t : failed) {
                sb.append("  ").append(t.getId()).append(": ")
                        .append(t.getDescription()).append("\n");
                sb.append("  失败原因：").append(t.getResult()).append("\n");
            }
        }

        List<Task> pending = getPendingTasks();
        if (!pending.isEmpty()) {
            sb.append("\n未完成步骤（需重新规划替代方案）：\n");
            for (Task t : pending) {
                String deps = t.getDependencies().isEmpty() ? "无"
                        : String.join(",", t.getDependencies());
                sb.append("  【").append(t.getDescription()).append("】（原依赖: ")
                        .append(deps).append("）\n");
            }
        }

        return sb.toString();
    }

    /** 任务总数 */
    public int size() {
        return tasks.size();
    }

    // ──────── 图完整性检测 ────────

    /**
     * 使用 DFS 检测循环依赖。{@code visited} 记录已检查节点，{@code recStack}
     * 记录当前递归路径；再次遇到路径内节点即存在回边。时间复杂度为 O(V+E)。
     */
    public boolean hasCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (String id : taskOrder) {
            if (hasCycleFrom(id, visited, recStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleFrom(String currentId, Set<String> visited, Set<String> recStack) {
        // 已经验证过无环的节点 → 跳过
        if (visited.contains(currentId)) return false;

        // 标记为已访问 + 入栈
        visited.add(currentId);
        recStack.add(currentId);

        Task current = tasks.get(currentId);
        if (current != null) {
            for (String depId : current.getDependencies()) {
                // 依赖的节点还在当前路径上 → 回边 → 有环
                if (recStack.contains(depId)) {
                    return true;
                }
                if (hasCycleFrom(depId, visited, recStack)) {
                    return true;
                }
            }
        }

        // 回溯：从当前路径移除
        recStack.remove(currentId);
        return false;
    }

    /**
     * 获取所有"被阻塞"的 PENDING Task 及其阻塞原因。
     *
     * 用于死锁诊断：当 getReadyTasks() 返回空但 isAllComplete() 为 false 时，
     * 调用此方法找出谁在等谁，帮助排查是循环依赖还是依赖了不存在的 Task。
     */
    public Map<String, String> getBlockedTasks() {
        Map<String, String> blocked = new LinkedHashMap<>();
        for (Task t : getAllTasks()) {
            if (t.getStatus() != Task.Status.PENDING) continue;
            List<String> unmet = new ArrayList<>();
            for (String depId : t.getDependencies()) {
                Task dep = tasks.get(depId);
                if (dep == null) {
                    unmet.add(depId + "（不存在）");
                } else if (dep.getStatus() != Task.Status.COMPLETED) {
                    unmet.add(depId + "（" + dep.getStatus() + "）");
                }
            }
            if (!unmet.isEmpty()) {
                blocked.put(t.getId(), "依赖未满足: " + String.join(", ", unmet));
            }
        }
        return blocked;
    }

    /** 打印计划摘要 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("执行计划（共 ").append(tasks.size()).append(" 个步骤）：\n");
        List<Task> ordered = getAllTasks();
        int i = 1;
        for (Task t : ordered) {
            String deps = t.getDependencies().isEmpty()
                    ? "无"
                    : String.join(", ", t.getDependencies());
            sb.append(String.format("  %d. %s — %s（依赖: %s）\n",
                    i++, t.getDescription(), t.getId(), deps));
        }
        return sb.toString();
    }

    public String buildReport(){
        StringBuilder sb = new StringBuilder();
        sb.append("执行计划汇总（共 ").append(tasks.size()).append(" 个步骤）：\n");
        getAllTasks().stream()
                .forEach(t -> {
                    String icon = switch (t.getStatus()) {
                        case COMPLETED -> "[OK]";
                        case FAILED -> "[FAIL]";
                        case IN_PROGRESS -> "[...]";
                        case PENDING -> "[ ]";
                    };
                    sb.append("  ").append(icon).append(" ").append(t.getId())
                            .append(" — ").append(t.getDescription()).append("\n");
                    if (t.getResult() != null && !t.getResult().isBlank()) {
                        sb.append("    ").append(t.getResult()).append("\n");
                    }
                });
        return sb.toString();
    }
}
