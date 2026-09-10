package com.xu.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 顺序运行 trial，避免个人电脑资源竞争污染耗时；每次创建新目录和新 JVM。 */
public final class EvalRunner {
    public record Config(String adapter, String profile, String model, int trials,
                         int timeoutSeconds, int maxCalls, long maxTokens, int maxTools,
                         int maxTurns) {
        public Config {
            if (!List.of("oracle", "unchanged", "scripted", "live").contains(adapter)
                    || !List.of("react", "no-goal", "review", "plan", "team").contains(profile)
                    || trials < 1 || timeoutSeconds < 1 || maxCalls < 1 || maxTokens < 1
                    || maxTools < 1 || maxTurns < 1) throw new IllegalArgumentException("Invalid eval configuration");
            if (adapter.equals("scripted") && !List.of("react", "no-goal").contains(profile))
                throw new IllegalArgumentException("Scripted smoke supports react/no-goal only");
        }
    }

    public EvalResult run(EvalTask task, int trial, Config config, Path runRoot) throws Exception {
        Path evidence = runRoot.resolve(task.id()).resolve("trial-" + trial).toAbsolutePath();
        Path workspace = evidence.resolve("workspace");
        Files.createDirectories(evidence);
        EvalFiles.writeFiles(workspace, task.files());
        EvalFiles.json(evidence.resolve("task.json"), task);
        long start = System.nanoTime();
        long agentMs = 0, gradingMs = 0;
        String status, error = "";
        Map<String, Boolean> checks = Map.of();
        EvalAgentMain.Result worker = new EvalAgentMain.Result("SUCCESS", 0, 0, false, 0, 0, false, "");
        try {
            if (config.adapter().equals("oracle")) EvalFiles.writeFiles(workspace, task.reference());
            else if (!config.adapter().equals("unchanged")) worker = runAgent(task, config, workspace, evidence);
            agentMs = elapsed(start);
            long gradeStart = System.nanoTime();
            var grade = new EvalGrader().grade(task, workspace, evidence.resolve("verification"));
            gradingMs = elapsed(gradeStart);
            checks = new LinkedHashMap<>(grade.checks());
            status = grade.status();
            boolean actualAgent = List.of("live", "scripted").contains(config.adapter());
            if (actualAgent && !task.fault().equals("none")) {
                checks.put("faultTriggered", worker.faultTriggered());
                if (status.equals("PASS") && !worker.faultTriggered()) status = "SCENARIO_NOT_EXERCISED";
            }
            if (actualAgent && !worker.runtimeStatus().equals("SUCCESS")) status = worker.runtimeStatus();
            error = worker.error();
        } catch (Exception e) {
            status = "INFRA_ERROR";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (agentMs == 0) agentMs = elapsed(start);
        }
        EvalResult result = new EvalResult(task.id(), task.family(), task.category(), trial,
                config.adapter(), config.profile(), config.model(), status, worker.runtimeStatus(),
                agentMs, gradingMs, worker.inputTokens(), worker.outputTokens(), worker.usageComplete(),
                worker.llmCalls(), worker.toolCalls(), worker.faultTriggered(), checks,
                runRoot.toAbsolutePath().relativize(evidence).toString().replace('\\', '/'), error);
        EvalFiles.json(evidence.resolve("result.json"), result);
        return result;
    }

    private EvalAgentMain.Result runAgent(EvalTask task, Config config, Path workspace, Path evidence) throws Exception {
        Path agentOutput = evidence.resolve("agent");
        Files.createDirectories(agentOutput);
        var command = List.of(EvalProcess.jdk("java"), "-Dfile.encoding=UTF-8", "-cp", EvalProcess.classpath(),
                EvalAgentMain.class.getName(), agentOutput.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectErrorStream(true).redirectOutput(agentOutput.resolve("process.log").toFile());
        // shell 运行 javac/java 时使用与评测宿主相同的 JDK。
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin")
                + java.io.File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
        Process process = builder.start();
        var request = new EvalAgentMain.Request(task.instructions(), config.adapter(), config.profile(), config.model(),
                config.maxCalls(), config.maxTokens(), config.maxTools(), config.maxTurns(), task.fault(),
                config.adapter().equals("scripted") ? task.reference() : Map.of());
        try {
            try (var stdin = process.getOutputStream()) { EvalFiles.JSON.writeValue(stdin, request); }
            boolean finished = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                EvalProcess.stop(process);
                return partial(agentOutput, "TIMEOUT", "Agent exceeded wall-clock budget");
            }
            Path result = agentOutput.resolve("worker.json");
            if (process.exitValue() != 0 || !Files.exists(result))
                return partial(agentOutput, "AGENT_ERROR", "Worker exited " + process.exitValue() + "; see process.log");
            return EvalFiles.JSON.readValue(result.toFile(), EvalAgentMain.Result.class);
        } catch (Exception e) {
            EvalProcess.stop(process);
            throw e;
        }
    }

    /** 超时后只恢复已经观察到的 token；缺失用量标记为不完整，不伪造为零成本。 */
    static EvalAgentMain.Result partial(Path output, String status, String error) throws Exception {
        Path pointer = output.resolve("observation.json");
        if (!Files.exists(pointer)) return new EvalAgentMain.Result(status,0,0,false,0,0,false,error);
        var observation = EvalFiles.JSON.readTree(pointer.toFile());
        Path events = output.resolve(observation.path("directory").asText()).resolve("events.jsonl");
        var snapshot = com.xu.observability.ExecutionSnapshot.recover(events);
        return new EvalAgentMain.Result(status, snapshot.inputTokens(), snapshot.outputTokens(), false,
                snapshot.llmCalls(), snapshot.requestedTools(), snapshot.faultTriggered(), error);
    }
    private static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
}
