package com.xu.ui;

import com.xu.hitl.ApprovalResult;
import com.xu.observability.MdcScope;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiHitlHandlerTest {

    @Test
    void shouldBridgeApprovalWithPreQueueRedactionAndSessionGrant()
            throws Exception {
        QueueUiEventSink events = new QueueUiEventSink();
        TuiHitlHandler handler = new TuiHitlHandler(events);
        String secret = String.join(
                "", "sk", "-", "super-secret-value-123456");

        CompletableFuture<ApprovalResult> first =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "write_file",
                        Map.of("path", "README.md", "api_key", secret)));

        UiEvent.ApprovalRequested request =
                (UiEvent.ApprovalRequested) events.poll(
                        2, TimeUnit.SECONDS);
        assertFalse(request.toString().contains(secret));
        request.response().complete(new ApprovalResult(
                ApprovalResult.Type.APPROVED_ALL, null));
        assertEquals(
                ApprovalResult.Type.APPROVED_ALL,
                first.get(2, TimeUnit.SECONDS).type());

        ApprovalResult second = handler.requestApproval(
                "write_file", Map.of("path", "pom.xml"));
        assertTrue(second.isApproved());
        assertTrue(events.isEmpty());

        handler.clearSessionState();
        CompletableFuture<ApprovalResult> afterClear =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "write_file", Map.of("path", "pom.xml")));
        UiEvent.ApprovalRequested next =
                (UiEvent.ApprovalRequested) events.poll(
                        2, TimeUnit.SECONDS);
        next.response().complete(new ApprovalResult(
                ApprovalResult.Type.REJECTED, "test"));
        assertEquals(
                ApprovalResult.Type.REJECTED,
                afterClear.get(2, TimeUnit.SECONDS).type());
    }

    @Test
    void approvalCarriesPlanTaskLabelForConcurrentWorkers()
            throws Exception {
        QueueUiEventSink events = new QueueUiEventSink();
        TuiHitlHandler handler = new TuiHitlHandler(events);
        CompletableFuture<ApprovalResult> waiting =
                CompletableFuture.supplyAsync(() -> {
                    try (MdcScope ignored =
                                 MdcScope.put("task_id", "task_7")) {
                        return handler.requestApproval(
                                "write_file",
                                Map.of("path", "one.txt"));
                    }
                });

        UiEvent.ApprovalRequested request =
                (UiEvent.ApprovalRequested) events.poll(
                        2, TimeUnit.SECONDS);
        assertEquals("task_7", request.taskLabel());
        handler.completeApproval(
                request,
                new ApprovalResult(
                        ApprovalResult.Type.REJECTED, "test"));
        assertEquals(
                ApprovalResult.Type.REJECTED,
                waiting.get(2, TimeUnit.SECONDS).type());
    }

    @Test
    void cancellingShouldReleasePendingWorker() throws Exception {
        QueueUiEventSink events = new QueueUiEventSink();
        TuiHitlHandler handler = new TuiHitlHandler(events);
        CompletableFuture<ApprovalResult> waiting =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "execute_command", Map.of("command", "mvn test")));
        events.poll(2, TimeUnit.SECONDS);

        handler.cancelPending("cancelled");

        ApprovalResult result = waiting.get(2, TimeUnit.SECONDS);
        assertEquals(ApprovalResult.Type.REJECTED, result.type());
        assertEquals("cancelled", result.reason());
    }

    @Test
    void lateUiDecisionMustNotOverrideOrDisplayCancelledApproval()
            throws Exception {
        QueueUiEventSink events = new QueueUiEventSink();
        TuiHitlHandler handler = new TuiHitlHandler(events);
        CompletableFuture<ApprovalResult> waiting =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "write_file", Map.of("path", "one.txt")));
        UiEvent.ApprovalRequested request =
                (UiEvent.ApprovalRequested) events.poll(
                        2, TimeUnit.SECONDS);

        handler.cancelPending("cancelled");
        assertFalse(handler.completeApproval(
                request,
                new ApprovalResult(
                        ApprovalResult.Type.APPROVED_ALL, null)));
        assertEquals(
                ApprovalResult.Type.REJECTED,
                waiting.get(2, TimeUnit.SECONDS).type());
        assertFalse(handler.approvedAllTools().contains("write_file"));
    }

    @Test
    void approveAllShouldReleaseQueuedRequestsForSameTool()
            throws Exception {
        QueueUiEventSink events = new QueueUiEventSink();
        TuiHitlHandler handler = new TuiHitlHandler(events);
        CompletableFuture<ApprovalResult> first =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "write_file", Map.of("path", "one.txt")));
        CompletableFuture<ApprovalResult> second =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "write_file", Map.of("path", "two.txt")));
        UiEvent.ApprovalRequested visible =
                (UiEvent.ApprovalRequested) events.poll(
                        2, TimeUnit.SECONDS);
        events.poll(2, TimeUnit.SECONDS);

        handler.completeApproval(visible, new ApprovalResult(
                ApprovalResult.Type.APPROVED_ALL, null));

        assertTrue(first.get(2, TimeUnit.SECONDS).isApproved());
        assertTrue(second.get(2, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void registrationRacingCancellationMustNeverLoseAWaiter()
            throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            QueueUiEventSink events = new QueueUiEventSink();
            TuiHitlHandler handler = new TuiHitlHandler(events);
            handler.beginRun();
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<ApprovalResult> waiting =
                    CompletableFuture.supplyAsync(() -> {
                        await(start);
                        return handler.requestApproval(
                                "execute_command",
                                Map.of("command", "mvn test"));
                    });
            CompletableFuture<Void> cancelling =
                    CompletableFuture.runAsync(() -> {
                        await(start);
                        handler.cancelPending("cancelled");
                    });

            start.countDown();
            ApprovalResult result =
                    waiting.get(2, TimeUnit.SECONDS);
            cancelling.get(2, TimeUnit.SECONDS);
            assertEquals(ApprovalResult.Type.REJECTED, result.type());
        }
    }

    @Test
    void approveAllAndCancelMustHaveOneLinearizableWinner()
            throws Exception {
        QueueUiEventSink events = new QueueUiEventSink();
        TuiHitlHandler handler = new TuiHitlHandler(events);
        CompletableFuture<ApprovalResult> waiting =
                CompletableFuture.supplyAsync(() -> handler.requestApproval(
                        "write_file", Map.of("path", "one.txt")));
        UiEvent.ApprovalRequested request =
                (UiEvent.ApprovalRequested) events.poll(
                        2, TimeUnit.SECONDS);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> approving =
                CompletableFuture.runAsync(() -> {
                    await(start);
                    handler.completeApproval(
                            request,
                            new ApprovalResult(
                                    ApprovalResult.Type.APPROVED_ALL,
                                    null));
                });
        CompletableFuture<Void> cancelling =
                CompletableFuture.runAsync(() -> {
                    await(start);
                    handler.cancelPending("cancelled");
                });

        start.countDown();
        ApprovalResult result = waiting.get(2, TimeUnit.SECONDS);
        approving.get(2, TimeUnit.SECONDS);
        cancelling.get(2, TimeUnit.SECONDS);

        if (result.type() == ApprovalResult.Type.REJECTED) {
            assertFalse(handler.approvedAllTools().contains("write_file"));
        } else {
            assertEquals(
                    ApprovalResult.Type.APPROVED_ALL, result.type());
            assertTrue(handler.approvedAllTools().contains("write_file"));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }
}
