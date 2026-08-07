package com.xu.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划中的单个任务步骤。
 *
 * 每个 Task 是 LLM 拆解出来的一个子目标，执行时会作为子 Agent 的输入。
 * 命名对齐 PaiCLI 原版。
 */
public class Task {

    public enum Status {
        PENDING,       // 等待依赖满足
        IN_PROGRESS,   // 正在执行
        COMPLETED,     // 成功完成
        FAILED         // 执行失败
    }

    private final String id;
    private final String description;
    private final List<String> dependencies;  // 依赖的 task id 列表
    private Status status;
    private String result;  // 执行输出（成功的内容或失败原因）

    // ---- 构造函数 ----

    /** 无依赖的 Task */
    public Task(String id, String description) {
        this.id = id;
        this.description = description;
        this.dependencies = new ArrayList<>();
        this.status = Status.PENDING;
        this.result = "";
    }

    /** 带初始依赖列表的 Task */
    public Task(String id, String description, List<String> dependencies) {
        this.id = id;
        this.description = description;
        this.dependencies = new ArrayList<>(dependencies);  // 防御性拷贝
        this.status = Status.PENDING;
        this.result = "";
    }

    // 访问器

    public String getId() { return id; }
    public String getDescription() { return description; }
    public List<String> getDependencies() { return dependencies; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }

    public void setStatus(Status status) { this.status = status; }
    public void setResult(String result) { this.result = result; }

    /** 追加一个依赖 */
    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    @Override
    public String toString() {
        return String.format("%s [%s] %s", id, status, description);
    }
}
