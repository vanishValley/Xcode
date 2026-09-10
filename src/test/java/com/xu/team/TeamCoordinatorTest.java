package com.xu.team;

import com.xu.llm.LlmClient;
import com.xu.observability.Tracing;
import com.xu.tool.*;
import com.xu.tool.impl.ReadFileTool;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamCoordinatorTest {
    @TempDir Path directory;

    @Test void fullTeamFlowScopesWorkerToolsCollectsResultsThenLeadWrites() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path data = directory.resolve("data");
        Files.writeString(project.resolve("source.txt"), "evidence");
        AtomicInteger writes = new AtomicInteger(), workerCalls = new AtomicInteger();
        AtomicBoolean workerDenied = new AtomicBoolean(), workerRead = new AtomicBoolean();
        ToolRegistry base = new ToolRegistry();
        base.register(new ReadFileTool());
        base.register(tool("write_file", args -> { writes.incrementAndGet(); return "changed"; }));
        LlmClient model = new LlmClient("", "fake") {
            private int phase;
            @Override public synchronized Message chatRaw(List<Message> messages, List<Map<String, Object>> tools) {
                String text = messages.stream().map(m -> m.content == null ? "" : m.content).reduce("", (a, b) -> a + "\n" + b);
                boolean worker = text.contains("你是主 Agent 委派的只读调查成员");
                if (worker) {
                    assertFalse(tools.toString().contains("spawn_agent"));
                    assertFalse(tools.toString().contains("write_file"));
                    if (workerCalls.incrementAndGet() == 1) {
                        Message reply = new Message("assistant", null);
                        reply.toolCalls = List.of(call("read", "read_file", "{\"path\":\"source.txt\"}"),
                                call("denied", "write_file", "{}"));
                        return reply;
                    }
                    workerDenied.set(messages.stream().anyMatch(m -> "tool".equals(m.role) && m.content.contains("工具不存在")));
                    workerRead.set(messages.stream().anyMatch(m -> "tool".equals(m.role) && m.content.equals("evidence")));
                    return new Message("assistant", "source.txt 给出了证据，未运行测试。");
                }
                if (phase == 0) { phase = 1; return reply("spawn", "spawn_agent", "{\"task\":\"调查 source.txt\"}"); }
                if (phase == 1) {
                    Matcher found = Pattern.compile("\"resultId\":\"(result-[^\"]+)\"").matcher(text);
                    if (!found.find()) return reply("wait", "wait_agents", "{\"timeoutMs\":1000}");
                    phase = 2;
                    return reply("result", "get_agent_result", "{\"resultId\":\"" + found.group(1) + "\"}");
                }
                if (phase == 2) { phase = 3; return reply("write", "write_file", "{}"); }
                return new Message("assistant", "调查完成，主 Agent 已集中修改。验证依据：工具返回 changed。");
            }
        };
        TeamCoordinator coordinator = new TeamCoordinator(model, base, null, null, project, data,
                Tracing.noop(), UiEventSink.noop(), new CancellationToken());
        String report = coordinator.execute("调查并修复");
        assertTrue(report.contains("Team 执行状态：SUCCESS"), report);
        assertTrue(workerDenied.get()); assertTrue(workerRead.get()); assertEquals(1, writes.get());
        try (var teams = Files.list(data.resolve("teams"))) {
            Path team = teams.findFirst().orElseThrow();
            String events = Files.readString(team.resolve("events.jsonl"));
            assertTrue(events.contains("REQUEST_ATTEMPTED")); assertTrue(events.contains("RESULT"));
            assertTrue(Files.readString(team.resolve("manifest.json")).contains("SUCCESS"));
            try (var results = Files.list(team.resolve("results"))) { assertEquals(1, results.count()); }
        }
    }

    @Test void permissionViewRetainsExecutionChecksAndRejectsOutsideReadsAndDynamicMcp() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path outside = directory.resolve("outside.txt"); Files.writeString(outside, "private");
        Files.writeString(project.resolve("inside.txt"), "public");
        ToolRegistry base = new ToolRegistry(); base.register(new ReadFileTool());
        AtomicInteger approvals = new AtomicInteger();
        base.register(tool("write_file", args -> { approvals.incrementAndGet(); return "approved"; }));
        try (TeamRuntime runtime = new TeamRuntime(TeamConfig.defaults(), new CancellationToken(),
                (rt, id, task, token) -> (input, hooks) -> null, null, null)) {
            TeamToolRegistry child = new TeamToolRegistry(base, runtime, "child", false, project, new CancellationToken());
            base.register(tool("mcp__unknown", args -> "unsafe"));
            assertNull(child.get("write_file")); assertNull(child.get("spawn_agent")); assertNull(child.get("mcp__unknown"));
            ToolExecutionResult denied = child.get("read_file").executeObserved(Map.of("path", outside.toString()));
            assertFalse(denied.success()); assertEquals("CAPABILITY_DENIED", denied.errorType());
            assertEquals("public", child.get("read_file").executeObserved(Map.of("path", "inside.txt")).content());
            TeamToolRegistry lead = new TeamToolRegistry(base, runtime, "lead", true, project, new CancellationToken());
            assertEquals("approved", lead.get("write_file").executeObserved(Map.of()).content());
            assertEquals(1, approvals.get());
        }
    }

    static LlmClient.Message reply(String id, String name, String arguments) {
        LlmClient.Message reply = new LlmClient.Message("assistant", null);
        reply.toolCalls = List.of(call(id, name, arguments)); return reply;
    }
    static LlmClient.ToolCall call(String id, String name, String arguments) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(); call.id = id;
        call.function = new LlmClient.Function(); call.function.name = name; call.function.arguments = arguments; return call;
    }
    private static Tool tool(String name, java.util.function.Function<Map<String, Object>, String> run) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String execute(Map<String, Object> args) { return run.apply(args); }
        };
    }

    @Test void deadlineInterruptsWaitingLeadAndWorkerWithoutLeavingAnUnsafeRun() throws Exception {
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        AtomicInteger leadCalls = new AtomicInteger();
        LlmClient model = new LlmClient("", "fake") {
            @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools) throws java.io.IOException {
                boolean worker = messages.stream().anyMatch(m -> m.content != null && m.content.contains("你是主 Agent 委派的只读调查成员"));
                if (worker) {
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException e) { workerInterrupted.set(true); throw new java.io.InterruptedIOException("cancelled"); }
                }
                if (leadCalls.incrementAndGet() == 1) return reply("spawn", "spawn_agent", "{\"task\":\"长调查\"}");
                return reply("wait", "wait_agents", "{\"timeoutMs\":30000}");
            }
        };
        TeamConfig config = new TeamConfig(1, 1, 2, 1, 4, 100, 500000, 1024,
                java.time.Duration.ofMillis(800), java.time.Duration.ofSeconds(2));
        CancellationToken root = new CancellationToken();
        TeamCoordinator coordinator = new TeamCoordinator(model, new ToolRegistry(), null, null,
                directory, directory.resolve("data"), Tracing.noop(), UiEventSink.noop(), root, config);
        try {
            String report = coordinator.execute("有截止时间的调查");
            assertTrue(report.contains("BUDGET_EXCEEDED"), report);
            assertTrue(workerInterrupted.get()); assertTrue(root.isReusable());
            assertFalse(report.contains("INTERRUPTED_UNSAFE"), report);
        } finally { Thread.interrupted(); }
    }
}
