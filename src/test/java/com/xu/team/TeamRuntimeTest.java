package com.xu.team;

import com.xu.agent.Agent;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.xu.team.TeamTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamRuntimeTest {
    private static final Task TASK = new Task("调查登录", "已知现象", List.of(), List.of("给出证据"), "结论");
    private static Agent.RunResult completed(String text) {
        return new Agent.RunResult(text, "SUCCESS", 1, 1, 0, 0, false, 10, 5);
    }
    private static TeamRuntime runtime(TeamRuntime.Factory factory) {
        return new TeamRuntime(TeamConfig.defaults(), new CancellationToken(), factory, null, null);
    }
    private static void untilResults(TeamRuntime runtime, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runtime.results().size() < count && System.nanoTime() < end)
            runtime.await(0, 32, Duration.ofMillis(500));
        assertEquals(count, runtime.results().size());
    }

    @Test void spawnIsAsyncAndSameOperationCreatesOnlyOneMember() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> {
            factories.incrementAndGet();
            return (input, hooks) -> { entered.countDown(); release.await(); return completed("done"); };
        })) {
            Receipt first = runtime.spawn("same-call", TASK);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(first.agentId(), runtime.spawn("same-call", TASK).agentId());
            assertEquals(1, factories.get());
            assertFalse(runtime.hooks("lead", true).finishBlocker().isBlank());
            release.countDown(); untilResults(runtime, 1);
            runtime.hooks("lead", true).receive();
            assertEquals("", runtime.hooks("lead", true).finishBlocker());
            assertThrows(TeamException.class, () -> runtime.spawn("new", TASK));
        } finally { release.countDown(); }
    }

    @Test void continuationIsSerialAndKeepsSameMemberAcrossFinishRace() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger(), active = new AtomicInteger(), maxActive = new AtomicInteger(), runs = new AtomicInteger();
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> {
            factories.incrementAndGet();
            return (input, hooks) -> {
                int n = runs.incrementAndGet();
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { if (n == 1) { entered.countDown(); release.await(); } return completed(input); }
                finally { active.decrementAndGet(); }
            };
        })) {
            String id = runtime.spawn("spawn", TASK).agentId();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Delivery delivery = runtime.send("continue", id, Action.CONTINUE, "调查数据库", "");
            assertEquals(delivery, runtime.send("continue", id, Action.CONTINUE, "调查数据库", ""));
            release.countDown(); untilResults(runtime, 2);
            runtime.send("continue-again", id, Action.CONTINUE, "补充证据", "");
            untilResults(runtime, 3);
            assertEquals(1, factories.get()); assertEquals(1, maxActive.get());
            assertNotEquals(runtime.results().get(0).runId(), runtime.results().get(1).runId());
        } finally { release.countDown(); }
    }

    @Test void notifyDoesNotWakeIdleMemberAndIsConsumedOnContinue() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<String> received = new AtomicReference<>("");
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> (input, hooks) -> {
            runs.incrementAndGet();
            received.set(hooks.receive().stream().map(m -> m.content).reduce("", (a, b) -> a + b));
            return completed("done");
        })) {
            String id = runtime.spawn("spawn", TASK).agentId(); untilResults(runtime, 1);
            runtime.send("notify", id, Action.NOTIFY, "字段已变更", "");
            assertEquals(State.IDLE, runtime.snapshots().get(0).state());
            assertEquals(1, runs.get());
            assertFalse(runtime.hooks("lead", true).finishBlocker().isBlank());
            runtime.send("continue", id, Action.CONTINUE, "请检查通知", ""); untilResults(runtime, 2);
            assertEquals(0, runtime.snapshots().get(0).pendingMessages());
            assertTrue(received.get().contains("字段已变更"));
        }
    }

    @Test void answerArrivingBeforePauseIsNotLost() throws Exception {
        CountDownLatch asked = new CountDownLatch(1), canFinish = new CountDownLatch(1);
        AtomicReference<String> question = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> (input, hooks) -> {
            if (runs.incrementAndGet() == 1) {
                question.set(rt.report(id, "question-op", "QUESTION", "是否支持跨域 Cookie？", true));
                asked.countDown(); canFinish.await();
                assertTrue(hooks.pauseRequested());
                return new Agent.RunResult("question", "NEEDS_INPUT", 1, 1, 1, 0, false, 0, 0);
            }
            assertTrue(input.contains("支持")); assertFalse(hooks.pauseRequested());
            return completed("已确认");
        })) {
            String id = runtime.spawn("spawn", TASK).agentId();
            assertTrue(asked.await(3, TimeUnit.SECONDS));
            assertThrows(TeamException.class, () -> runtime.send("bad", id, Action.ANSWER, "支持", "wrong"));
            runtime.send("answer", id, Action.ANSWER, "需要支持跨域 Cookie", question.get());
            canFinish.countDown(); untilResults(runtime, 2);
            assertEquals(State.IDLE, runtime.snapshots().get(0).state());
            assertEquals("SUCCESS", runtime.results().get(1).outcome());
        } finally { canFinish.countDown(); }
    }

    @Test void exclusivePhaseBlocksSpawnAndActiveMembersBlockWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> (input, hooks) -> {
            entered.countDown(); release.await(); return completed("done");
        })) {
            runtime.spawn("spawn", TASK); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(TeamException.class, runtime::exclusive);
            release.countDown(); untilResults(runtime, 1);
            try (AutoCloseable lease = runtime.exclusive()) {
                assertThrows(TeamException.class, () -> runtime.spawn("during-write", TASK));
            }
            assertEquals(1, runtime.workspaceEpoch());
        } finally { release.countDown(); }
    }

    @Test void cancelledQueuedMemberNeverRunsAndRunningCancelWaitsForExit() throws Exception {
        TeamConfig config = new TeamConfig(1, 2, 3, 2, 4, 20, 100000, 1000, Duration.ofMinutes(1), Duration.ofSeconds(2));
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        try (TeamRuntime runtime = new TeamRuntime(config, new CancellationToken(), (rt, id, task, token) -> (input, hooks) -> {
            ran.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { interrupted.countDown(); release.await(); }
            return completed("done");
        }, null, null)) {
            String a = runtime.spawn("a", TASK).agentId(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            String b = runtime.spawn("b", TASK).agentId();
            runtime.stop(b, "取消排队"); runtime.stop(a, "取消运行");
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(State.CANCELLING, runtime.snapshots().get(0).state());
            assertEquals(State.CANCELLED, runtime.snapshots().get(1).state());
            release.countDown(); untilResults(runtime, 1);
            assertEquals(1, ran.get()); assertEquals("CANCELLED", runtime.results().get(0).outcome());
        } finally { release.countDown(); }
    }

    @Test void unknownIdsAndCreatingCapacityAreRejected() throws Exception {
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> (input, hooks) -> completed("done"))) {
            assertThrows(TeamException.class, () -> runtime.send("x", "other-team", Action.NOTIFY, "msg", ""));
            assertThrows(TeamException.class, () -> runtime.result("other-result"));
            assertThrows(TeamException.class, () -> runtime.await(999, 1, Duration.ZERO));
        }
    }

    @Test void waitAndAutomaticInputDoNotDuplicateEventsAndTraceDoesNotBusyLoop() throws Exception {
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> (input, hooks) -> completed("done"))) {
            runtime.spawn("spawn", TASK); untilResults(runtime, 1);
            runtime.hooks("lead", true).receive();
            runtime.requestRecorded("lead", "request", "REQUEST_ATTEMPTED");
            Batch empty = runtime.await(0, 32, Duration.ofMillis(10));
            assertTrue(empty.timedOut()); assertTrue(empty.events().isEmpty());
            assertTrue(runtime.hooks("lead", true).receive().isEmpty());
        }
    }

    @Test void budgetReservationsBoundParallelRequestsAndMissingUsageStillCosts() {
        TeamConfig config = new TeamConfig(1, 1, 1, 1, 1, 3, 2500, 1000, Duration.ofMinutes(1), Duration.ZERO);
        TeamBudget budget = new TeamBudget(config);
        long first = budget.reserve(500);
        assertThrows(TeamException.class, () -> budget.reserve(500));
        budget.settle(first, 0);
        assertEquals(1500L, budget.snapshot().get("usedTokens"));
        assertThrows(TeamException.class, () -> budget.reserve(501));
    }

    @Test void cancellationDuringFactoryNeverStartsTheCreatedMember() throws Exception {
        CountDownLatch creating = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        ExecutorService creator = Executors.newSingleThreadExecutor();
        try (TeamRuntime runtime = runtime((rt, id, task, token) -> {
            creating.countDown(); release.await();
            return (input, hooks) -> { runs.incrementAndGet(); return completed("unexpected"); };
        })) {
            Future<Receipt> spawned = creator.submit(() -> runtime.spawn("spawn", TASK));
            assertTrue(creating.await(3, TimeUnit.SECONDS));
            runtime.stop(runtime.snapshots().get(0).agentId(), "创建时取消");
            release.countDown();
            assertEquals(State.CANCELLED, spawned.get(3, TimeUnit.SECONDS).state());
            assertEquals(0, runs.get());
        } finally { release.countDown(); creator.shutdownNow(); }
    }

    @Test void fullNotificationMailboxStillAllowsContinuationToDrainIt() throws Exception {
        TeamConfig config = new TeamConfig(1, 1, 2, 1, 1, 20, 100000, 1000, Duration.ofMinutes(1), Duration.ofSeconds(2));
        AtomicInteger received = new AtomicInteger();
        try (TeamRuntime runtime = new TeamRuntime(config, new CancellationToken(), (rt, id, task, token) ->
                (input, hooks) -> { received.addAndGet(hooks.receive().size()); return completed("done"); }, null, null)) {
            String id = runtime.spawn("spawn", TASK).agentId(); untilResults(runtime, 1);
            runtime.send("notification", id, Action.NOTIFY, "new evidence", "");
            assertThrows(TeamException.class, () -> runtime.send("overflow", id, Action.NOTIFY, "more", ""));
            runtime.send("continue", id, Action.CONTINUE, "process notification", ""); untilResults(runtime, 2);
            assertEquals(1, received.get());
        }
    }

    @Test void failedFactoryIsVisibleAndDoesNotConsumeLiveMemberSlot() throws Exception {
        TeamConfig config = new TeamConfig(1, 1, 3, 1, 1, 20, 100000, 1000, Duration.ofMinutes(1), Duration.ofSeconds(2));
        AtomicInteger factories = new AtomicInteger();
        try (TeamRuntime runtime = new TeamRuntime(config, new CancellationToken(), (rt, id, task, token) -> {
            if (factories.incrementAndGet() == 1) throw new IllegalStateException("factory error");
            return (input, hooks) -> completed("done");
        }, null, null)) {
            assertEquals(State.FAILED, runtime.spawn("first", TASK).state());
            assertEquals(State.FAILED, runtime.spawn("first", TASK).state());
            runtime.spawn("second", TASK); untilResults(runtime, 1);
            assertEquals(2, factories.get());
            assertEquals("SUCCESS", runtime.results().get(0).outcome());
        }
    }
}
