package com.xu.observability;

import com.xu.llm.LlmClient;
import com.xu.tool.ToolExecutor;
import com.xu.tool.ToolRegistry;
import com.xu.tool.impl.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionCaptureTest {
    @TempDir Path root;

    @Test void originalToolExecutorFeedsCaptureAndOriginalArtifactStore() throws Exception {
        try(var tracing=Tracing.forEvaluation(root.resolve("obs"))) {
            var capture=tracing.capture("test","write one file");
            Path staging=capture.directory();
            try(capture) {
                var registry=new ToolRegistry();registry.register(new WriteFileTool(root));
                var call=new LlmClient.ToolCall();call.id="call-1";call.function=new LlmClient.Function();
                call.function.name="write_file";call.function.arguments="{\"path\":\"hello.txt\",\"content\":\"hello\"}";
                assertTrue(new ToolExecutor(registry,tracing).execute(call).success());
                assertEquals(1,capture.snapshot().executedTools());
                assertEquals(0,capture.snapshot().requestedTools()); // 模型未调用，不伪造模型请求计数。
                tracing.artifacts().beginTrace(capture.traceId(),"PLAN","nested task");
                tracing.artifacts().completeTrace(capture.traceId(),"SUCCESS",false);
                assertTrue(Files.exists(staging)); // 子任务不能提前归档最外层采集。
            }
            assertFalse(Files.exists(staging));
            assertTrue(Files.exists(capture.directory().resolve("events.jsonl")));
            try(var files=Files.list(capture.directory())) {
                assertTrue(files.anyMatch(p->p.getFileName().toString().endsWith("-request.txt")));
            }
            assertEquals(capture.snapshot(),ExecutionSnapshot.recover(capture.directory().resolve("events.jsonl")));
        }
    }

    @Test void parallelSpansAggregateOnceAndDoNotCountNestedAgentTotals() throws Exception {
        try(var tracing=Tracing.forEvaluation(root.resolve("obs"));var capture=tracing.capture("team","work")) {
            var pool=Executors.newFixedThreadPool(4);
            try {
                var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int i=0;i<8;i++) jobs.add(pool.submit(ContextAwareTasks.wrap((Runnable)()-> {
                    try(var agent=tracing.start("agent.invoke").attribute("agent.usage.input_tokens",99999);
                        var llm=tracing.startClient("llm.chat")) {
                        llm.attribute("gen_ai.usage.input_tokens",20).attribute("gen_ai.usage.output_tokens",5)
                                .attribute("gen_ai.usage.complete",true).attribute("llm.tool_call_count",2);
                        // 即使指标也记录了同一次调用，Capture 只消费 llm.chat Span 一次。
                        tracing.metrics().recordLlm("fake","SUCCESS",1,20,5);
                    }
                })));
                for(var job:jobs)job.get();
            } finally { pool.shutdownNow(); }
            assertEquals(8,capture.snapshot().llmCalls());assertEquals(160,capture.snapshot().inputTokens());
            assertEquals(40,capture.snapshot().outputTokens());assertEquals(16,capture.snapshot().requestedTools());
            assertTrue(capture.snapshot().usageComplete());
        }
    }

    @Test void simultaneousCapturesStayIsolatedByTraceId() throws Exception {
        try(var tracing=Tracing.forEvaluation(root.resolve("obs"))) {
            var pool=Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(()->run(tracing,11));var second=pool.submit(()->run(tracing,29));
                assertEquals(11,first.get().inputTokens());assertEquals(29,second.get().inputTokens());
            } finally { pool.shutdownNow(); }
        }
    }
    private ExecutionSnapshot run(Tracing tracing,int input) {
        try(var capture=tracing.capture("test","request")) {
            try(var llm=tracing.startClient("llm.chat")) {
                llm.attribute("gen_ai.usage.input_tokens",input).attribute("gen_ai.usage.output_tokens",1)
                        .attribute("gen_ai.usage.complete",true);
            }
            return capture.snapshot();
        }
    }

    @Test void failedRequestPreservesKnownUsageAndMarksMissingUsage() throws Exception {
        try(var tracing=Tracing.forEvaluation(root.resolve("obs"))) {
            var capture=tracing.capture("test","work");
            try(capture) {
                try(var known=tracing.startClient("llm.chat")) {
                    known.attribute("gen_ai.usage.input_tokens",10).attribute("gen_ai.usage.output_tokens",3)
                            .attribute("gen_ai.usage.complete",true);
                }
                try(var failed=tracing.startClient("llm.chat")) { failed.fail(new java.io.IOException("disconnected")); }
                capture.outcome("AGENT_ERROR");
            }
            var recovered=ExecutionSnapshot.recover(capture.directory().resolve("events.jsonl"));
            assertEquals(2,recovered.llmCalls());assertEquals(10,recovered.inputTokens());assertFalse(recovered.usageComplete());
            assertTrue(Files.readString(capture.directory().resolve("completion.json")).contains("AGENT_ERROR"));
        }
    }
}
