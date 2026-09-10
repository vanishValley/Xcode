package com.xu.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在独立目录重建候选源码，编译后运行隐藏行为和回归检查。 */
public final class EvalGrader {
    public record Grade(String status, Map<String, Boolean> checks) {}

    public Grade grade(EvalTask task, Path workspace, Path verification) throws Exception {
        Files.createDirectories(verification);
        Map<String, Boolean> results = new LinkedHashMap<>();
        boolean constraints = true;
        Map<String, String> candidate = new LinkedHashMap<>(task.files());
        for (String name : task.files().keySet()) {
            Path source = EvalFiles.resolve(workspace, name);
            if (task.editable().contains(name)) {
                if (!Files.isRegularFile(source)) constraints = false;
                else candidate.put(name, Files.readString(source));
            } else if (!Files.isRegularFile(source)
                    || !Files.readString(source).equals(task.files().get(name))) constraints = false;
        }
        // 编译产物允许存在；新增源码和删改保护文件都违反题目明确约定。
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = workspace.relativize(path).toString().replace('\\', '/');
                if (!task.files().containsKey(name) && !name.endsWith(".class")
                        && !name.startsWith(".eval/")) constraints = false;
            }
        }
        results.put("constraints", constraints);
        EvalFiles.writeFiles(verification, candidate);
        for (var check : task.checks().entrySet())
            Files.writeString(verification.resolve(check.getKey() + ".java"), check.getValue());
        List<String> compile = new ArrayList<>(List.of(EvalProcess.jdk("javac"), "-encoding", "UTF-8", "-d", "."));
        try (var sources = Files.walk(verification)) {
            sources.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> compile.add(p.toString()));
        }
        var compilation = EvalProcess.run(compile, verification, verification.resolve("compile.log"), Duration.ofSeconds(30));
        results.put("compile", compilation.code() == 0);
        if (compilation.timedOut()) return new Grade("GRADING_TIMEOUT", results);
        if (compilation.code() != 0) return new Grade("COMPILE_FAILED", results);
        boolean timedOut = false;
        for (String check : task.checks().keySet()) {
            var exit = EvalProcess.run(List.of(EvalProcess.jdk("java"), "-ea", "-cp", ".", check),
                    verification, verification.resolve(check + ".log"), Duration.ofSeconds(10));
            timedOut |= exit.timedOut();
            results.put(check, exit.code() == 0);
        }
        String status = !constraints ? "CONSTRAINT_FAILED" : timedOut ? "GRADING_TIMEOUT"
                : !results.get("BehaviorCheck") ? "TASK_FAILED"
                : !results.get("RegressionCheck") ? "REGRESSION_FAILED"
                : results.values().stream().allMatch(Boolean::booleanValue) ? "PASS" : "TASK_FAILED";
        return new Grade(status, results);
    }
}
