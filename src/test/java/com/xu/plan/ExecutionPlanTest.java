package com.xu.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutionPlan 核心逻辑测试 —— 重点测 getReadyTasks（DAG 拓扑执行的关键方法）
 */
class ExecutionPlanTest {

    private ExecutionPlan plan;

    @BeforeEach
    void setUp() {
        plan = new ExecutionPlan();
    }

    @Test
    void allPendingWithNoDeps_shouldAllBeReady() {
        // 三个独立任务，无依赖 → 全部就绪
        plan.addTask(new Task("t1", "任务1"));
        plan.addTask(new Task("t2", "任务2"));
        plan.addTask(new Task("t3", "任务3"));

        List<Task> ready = plan.getReadyTasks();
        assertEquals(3, ready.size());
    }

    @Test
    void taskWithUnfinishedDep_shouldNotBeReady() {
        // t2 依赖 t1，t1 还是 PENDING → t2 不就绪
        plan.addTask(new Task("t1", "先建项目"));
        Task t2 = new Task("t2", "写代码");
        t2.addDependency("t1");
        plan.addTask(t2);

        List<Task> ready = plan.getReadyTasks();
        assertEquals(1, ready.size());
        assertEquals("t1", ready.get(0).getId());  // 只有 t1 就绪
    }

    @Test
    void afterDepCompletes_taskBecomesReady() {
        // 先让 t1 完成，t2 应该就绪
        plan.addTask(new Task("t1", "建项目"));
        Task t2 = new Task("t2", "写代码", List.of("t1"));
        plan.addTask(t2);

        plan.updateTask("t1", Task.Status.COMPLETED, "ok");

        List<Task> ready = plan.getReadyTasks();
        assertEquals(1, ready.size());
        assertEquals("t2", ready.get(0).getId());
    }

    @Test
    void parallelBranches_bothBecomeReady() {
        // t1 完成后，t2 和 t3 都依赖 t1 → 应该同时就绪（并行执行）
        plan.addTask(new Task("t1", "建项目"));
        plan.addTask(new Task("t2", "写 Main", List.of("t1")));
        plan.addTask(new Task("t3", "写 Controller", List.of("t1")));

        plan.updateTask("t1", Task.Status.COMPLETED, "ok");

        List<Task> ready = plan.getReadyTasks();
        assertEquals(2, ready.size());
        assertTrue(ready.stream().anyMatch(t -> t.getId().equals("t2")));
        assertTrue(ready.stream().anyMatch(t -> t.getId().equals("t3")));
    }

    @Test
    void isAllSuccess_shouldBeTrueWhenAllCompleted() {
        plan.addTask(new Task("t1", "任务1"));
        plan.addTask(new Task("t2", "任务2"));

        assertFalse(plan.isAllSuccess());

        plan.updateTask("t1", Task.Status.COMPLETED, "ok");
        assertFalse(plan.isAllSuccess());

        plan.updateTask("t2", Task.Status.COMPLETED, "ok");
        assertTrue(plan.isAllSuccess());
    }

    @Test
    void isAllSuccess_shouldBeFalseWhenAnyFailed() {
        plan.addTask(new Task("t1", "任务1"));
        plan.addTask(new Task("t2", "任务2"));

        plan.updateTask("t1", Task.Status.COMPLETED, "ok");
        plan.updateTask("t2", Task.Status.FAILED, "报错");

        assertFalse(plan.isAllSuccess());
        assertTrue(plan.isAllComplete());  // 但都到终态了
        assertEquals(1, plan.getFailedTasks().size());
    }

    // ──── 环检测 & 阻塞诊断 ────

    @Test
    void noCycleInLinearChain() {
        // t0 → t1 → t2  无环
        plan.addTask(new Task("t0", "步骤0"));
        plan.addTask(new Task("t1", "步骤1", List.of("t0")));
        plan.addTask(new Task("t2", "步骤2", List.of("t1")));

        assertFalse(plan.hasCycle());
    }

    @Test
    void noCycleInDiamondDependency() {
        // t0 → t1, t2 → t3  无环（菱形依赖）
        plan.addTask(new Task("t0", "入口"));
        plan.addTask(new Task("t1", "分支1", List.of("t0")));
        plan.addTask(new Task("t2", "分支2", List.of("t0")));
        plan.addTask(new Task("t3", "合并", List.of("t1", "t2")));

        assertFalse(plan.hasCycle());
    }

    @Test
    void hasCycleInSimpleLoop() {
        // t1 → t2 → t1  有环
        plan.addTask(new Task("t1", "任务1", List.of("t2")));
        plan.addTask(new Task("t2", "任务2", List.of("t1")));

        assertTrue(plan.hasCycle());
    }

    @Test
    void hasCycleInSelfLoop() {
        // t1 → t1  自环
        Task t = new Task("t1", "任务1");
        t.addDependency("t1");
        plan.addTask(t);

        assertTrue(plan.hasCycle());
    }

    @Test
    void getBlockedTasks_shouldShowUnmetDependencies() {
        // t1 COMPLETED, t2 PENDING(等 t1), t3 PENDING(等 t2)
        plan.addTask(new Task("t1", "已完成"));
        plan.addTask(new Task("t2", "被阻塞", List.of("t1")));
        plan.addTask(new Task("t3", "深度阻塞", List.of("t2")));

        // t1 还没完成，t2 和 t3 应该被阻塞
        Map<String, String> blocked = plan.getBlockedTasks();

        assertEquals(2, blocked.size());
        assertTrue(blocked.containsKey("t2"));
        assertTrue(blocked.get("t2").contains("t1"));   // 阻塞原因是 t1 还没完成
        assertTrue(blocked.containsKey("t3"));
    }

    @Test
    void getBlockedTasks_shouldFlagMissingDependency() {
        Task t = new Task("t1", "任务", List.of("不存在的id"));
        plan.addTask(t);

        Map<String, String> blocked = plan.getBlockedTasks();

        assertEquals(1, blocked.size());
        assertTrue(blocked.get("t1").contains("不存在"));
    }
}
