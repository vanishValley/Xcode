package com.xu.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanStoreTest {

    @TempDir
    Path tempDir;

    private PlanStore store;

    @BeforeEach
    void setUp() {
        store = new PlanStore(tempDir);
    }

    /** task_0 已完成、task_1 依赖 task_0 仍待执行 */
    private ExecutionPlan buildPlan() {
        ExecutionPlan plan = new ExecutionPlan();
        Task t0 = new Task("task_0", "创建 pom.xml", List.of());
        t0.setStatus(Task.Status.COMPLETED);
        t0.setResult("已创建，含 4 个依赖");
        Task t1 = new Task("task_1", "写主类", List.of("task_0"));  // 保持 PENDING
        plan.addTask(t0);
        plan.addTask(t1);
        return plan;
    }

    @Test
    void saveThenLoadShouldRoundTrip() {
        store.save(new PlanStore.Checkpoint("创建 demo", 1, buildPlan()));

        PlanStore.Checkpoint cp = store.load();
        assertNotNull(cp);
        assertEquals("创建 demo", cp.userRequest());
        assertEquals(1, cp.replanCount());
        assertEquals(2, cp.plan().getAllTasks().size());

        Task t0 = cp.plan().getTask("task_0");
        assertEquals(Task.Status.COMPLETED, t0.getStatus());
        assertEquals("已创建，含 4 个依赖", t0.getResult());

        Task t1 = cp.plan().getTask("task_1");
        assertEquals(Task.Status.PENDING, t1.getStatus());
        assertEquals(List.of("task_0"), t1.getDependencies());
    }

    @Test
    void loadShouldReturnNullWhenNoFile() {
        assertNull(store.load());
        assertFalse(store.exists());
    }

    @Test
    void inProgressShouldBecomeNonReplayableInterruptedFailureOnLoad() {
        ExecutionPlan plan = new ExecutionPlan();
        Task t = new Task("task_0", "跑一半崩了", List.of());
        t.setStatus(Task.Status.IN_PROGRESS);
        plan.addTask(t);
        store.save(new PlanStore.Checkpoint("x", 0, plan));

        PlanStore.Checkpoint cp = store.load();
        Task restored = cp.plan().getTask("task_0");
        assertEquals(Task.Status.FAILED, restored.getStatus());
        assertTrue(restored.getResult().startsWith(
                PlanStore.INTERRUPTED_RESULT_PREFIX));
        assertTrue(store.hasInterruptedTasks(cp));
    }

    @Test
    void deleteShouldRemoveCheckpoint() {
        store.save(new PlanStore.Checkpoint("x", 0, buildPlan()));
        assertTrue(store.exists());
        store.delete();
        assertFalse(store.exists());
        assertNull(store.load());
    }

    @Test
    void corruptedFileShouldLoadAsNull() throws Exception {
        Files.writeString(tempDir.resolve("plan_checkpoint.json"), "{ 非法 json ");
        assertNull(store.load());   // 损坏当"无 checkpoint"，不抛异常
    }

    @Test
    void readyTasksShouldReflectRestoredStatus() {
        store.save(new PlanStore.Checkpoint("x", 0, buildPlan()));
        PlanStore.Checkpoint cp = store.load();
        // task_0 COMPLETED、task_1 依赖它 → 恢复后 task_1 就绪（断点自动浮现）
        List<Task> ready = cp.plan().getReadyTasks();
        assertEquals(1, ready.size());
        assertEquals("task_1", ready.get(0).getId());
    }

    @Test
    void unknownPersistedStatusShouldFailClosedInsteadOfReplaying()
            throws Exception {
        Files.writeString(
                tempDir.resolve("plan_checkpoint.json"),
                """
                        {
                          "version": 1,
                          "userRequest": "x",
                          "replanCount": 0,
                          "tasks": [{
                            "id": "task_0",
                            "description": "unknown state",
                            "dependencies": [],
                            "status": "FUTURE_STATUS",
                            "result": ""
                          }]
                        }
                        """);

        PlanStore.Checkpoint checkpoint = store.load();
        assertNotNull(checkpoint);
        Task task = checkpoint.plan().getTask("task_0");
        assertEquals(Task.Status.FAILED, task.getStatus());
        assertTrue(store.hasInterruptedTasks(checkpoint));
        assertTrue(checkpoint.plan().getReadyTasks().isEmpty());
    }
}
