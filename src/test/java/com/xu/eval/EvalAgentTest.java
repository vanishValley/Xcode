package com.xu.eval;

import com.xu.llm.LlmClient;
import com.xu.observability.Tracing;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class EvalAgentTest {
    @TempDir Path root;
    @Test void budgetUsesSharedCaptureForBothModelEntrypoints() throws Exception {
        try(var tracing=Tracing.forEvaluation(root.resolve("obs"));var capture=tracing.capture("test","fix")) {
            LlmClient backend=new LlmClient("","fake",tracing) {
                @Override public Message chatRaw(List<Message> messages,List<Map<String,Object>> tools) {
                    try(var scope=tracing.startClient("llm.chat")) {
                        scope.attribute("gen_ai.usage.input_tokens",30).attribute("gen_ai.usage.output_tokens",10)
                                .attribute("gen_ai.usage.complete",true);
                        var reply=new Message("assistant","done");reply.inputTokens=30;reply.outputTokens=10;return reply;
                    }
                }
            };
            var request=new EvalAgentMain.Request("fix","live","react","fake",2,100,10,10,"none",Map.of());
            var token=new CancellationToken();token.beginRun();
            var client=new EvalAgentMain.BudgetedClient(backend,request,capture,token);
            client.chatRaw(List.of(),null);
            client.chatRaw(List.of(),null,512);
            assertEquals(60,capture.snapshot().inputTokens());assertEquals(20,capture.snapshot().outputTokens());
            assertEquals(2,capture.snapshot().llmCalls());
            assertThrows(IOException.class,()->client.chatRaw(List.of(),null));assertTrue(client.budgetExceeded);
            assertEquals(2,capture.snapshot().llmCalls()); // 拒绝准入不伪造一次模型调用。
        }
    }
    @Test void timeoutRecoveryReadsTheSameObservabilityJournal() throws Exception {
        try(var tracing=Tracing.forEvaluation(root.resolve("obs"));var capture=tracing.capture("test","fix")) {
            EvalFiles.json(root.resolve("observation.json"),Map.of("directory",root.relativize(capture.directory()).toString()));
            try(var completed=tracing.startClient("llm.chat")) {
                completed.attribute("gen_ai.usage.input_tokens",15).attribute("gen_ai.usage.output_tokens",4)
                        .attribute("gen_ai.usage.complete",true);
            }
            try(var inFlight=tracing.startClient("llm.chat")) {
                capture.event("fault",Map.of("type","read-once"));
                Files.writeString(capture.directory().resolve("events.jsonl"),"{incomplete",java.nio.file.StandardOpenOption.APPEND);
                var partial=EvalRunner.partial(root,"TIMEOUT","deadline");
                assertEquals(15,partial.inputTokens());assertEquals(2,partial.llmCalls());
                assertTrue(partial.faultTriggered());assertFalse(partial.usageComplete());
            }
        }
    }
    @Test void familyVariantsStayInSameSplit() throws Exception {
        var tasks=EvalMain.load(Path.of("benchmarks/java-v1/tasks").toAbsolutePath(),Map.of());
        assertEquals(20,tasks.size());
        Map<String,String> splits=new java.util.HashMap<>();
        for(var task:tasks){String previous=splits.putIfAbsent(task.family(),task.split());if(previous!=null)assertEquals(previous,task.split());}
    }
}
