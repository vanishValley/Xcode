package com.xu.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 独立入口：java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain ... */
public final class EvalMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("""
                    Xcode Eval
                      list      [--suite benchmarks/java-v1/tasks] [--split dev|holdout] [--task ID]
                      validate  [--suite PATH] [--out NEW_DIR]       校验错误初始版本与参考解
                      run       [--adapter scripted|live|oracle|unchanged] [--profile react|no-goal|review|plan|team]
                                [--trials 3] [--timeout 180] [--max-calls 40] [--max-tokens 120000]
                                [--max-tools 80] [--max-turns 20] [--model deepseek-chat] [--out NEW_DIR]
                      report    --run EXISTING_DIR                  从结果重新汇总
                      compare   --baseline DIR --candidate DIR     同题同预算配对比较
                    run 默认 scripted，无需 API；live 只读取环境变量 DEEPSEEK_API_KEY。
                    全部模式支持 --suite、--split、--task（report/compare 除外）。
                    """);
            return;
        }
        Map<String, String> options = options(args);
        if (args[0].equals("report")) {
            Path root = Path.of(required(options, "run")).toAbsolutePath();
            var manifest = EvalFiles.JSON.readTree(root.resolve("run.json").toFile());
            EvalReport.write(root, EvalReport.read(root), manifest.path("trials").asInt(), manifest.path("provenance").asText());
            System.out.println(root.resolve("report.md")); return;
        }
        if (args[0].equals("compare")) {
            EvalReport.compare(Path.of(required(options, "baseline")).toAbsolutePath(), Path.of(required(options, "candidate")).toAbsolutePath());
            return;
        }
        if (!List.of("list", "run", "validate").contains(args[0])) throw new IllegalArgumentException("Unknown command: " + args[0]);
        Path suite = Path.of(options.getOrDefault("suite", "benchmarks/java-v1/tasks")).toAbsolutePath();
        List<EvalTask> tasks = load(suite, options);
        if (args[0].equals("list")) {
            for (var task : tasks) System.out.printf("%s\t%s\t%s\t%s\t%s%n", task.id(), task.category(), task.difficulty(), task.split(), task.prompt());
            System.out.println("Tasks: " + tasks.size()); return;
        }
        Path root = Path.of(options.getOrDefault("out", "target/eval/" + args[0] + "-" + UUID.randomUUID())).toAbsolutePath();
        if (Files.exists(root)) throw new IllegalArgumentException("Output must be a new directory: " + root);
        Files.createDirectories(root);
        if (args[0].equals("validate")) {
            var reference = execute(tasks, suite, root.resolve("oracle"), config(options, "oracle", "react", 1));
            var initial = execute(tasks, suite, root.resolve("unchanged"), config(options, "unchanged", "react", 1));
            boolean valid = reference.stream().allMatch(EvalResult::passed)
                    && initial.stream().allMatch(r -> r.status().equals("TASK_FAILED")
                    && Boolean.TRUE.equals(r.checks().get("RegressionCheck")));
            EvalFiles.json(root.resolve("validation.json"), Map.of("valid", valid, "tasks", tasks.size(),
                    "referencePassed", reference.stream().filter(EvalResult::passed).count(),
                    "initialTaskFailed", initial.stream().filter(r -> r.status().equals("TASK_FAILED")).count()));
            System.out.println("Fixture validation: " + valid + " -> " + root);
            if (!valid) throw new IllegalStateException("Invalid fixtures; inspect oracle/unchanged reports");
            return;
        }
        String adapter = options.getOrDefault("adapter", "scripted");
        if (adapter.equals("live") && (System.getenv("DEEPSEEK_API_KEY") == null || System.getenv("DEEPSEEK_API_KEY").isBlank()))
            throw new IllegalArgumentException("Set DEEPSEEK_API_KEY before live evaluation");
        execute(tasks, suite, root, config(options, adapter, options.getOrDefault("profile", "react"), integer(options, "trials", 1)));
        System.out.println(root.resolve("report.md"));
    }

    static List<EvalTask> load(Path suite, Map<String, String> options) throws Exception {
        List<EvalTask> tasks = new ArrayList<>();
        try (var files = Files.list(suite)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                EvalTask task = EvalFiles.JSON.readValue(file.toFile(), EvalTask.class);
                if (!file.getFileName().toString().equals(task.id() + ".json")) throw new IllegalArgumentException("Task filename must match ID: " + file);
                if (options.containsKey("task") && !options.get("task").equals(task.id())) continue;
                if (options.containsKey("split") && !options.get("split").equals(task.split())) continue;
                tasks.add(task);
            }
        }
        if (tasks.isEmpty()) throw new IllegalArgumentException("No tasks selected: " + suite);
        return tasks;
    }

    static List<EvalResult> execute(List<EvalTask> tasks, Path suite, Path root, EvalRunner.Config config) throws Exception {
        Files.createDirectories(root);
        String provenance = switch (config.adapter()) {
            case "live" -> "LIVE_MODEL：真实模型运行";
            case "scripted" -> "SCRIPTED_SMOKE：脚本化模型驱动真实 Agent，仅校验框架";
            case "oracle" -> "ORACLE：直接应用参考解，仅校验裁判";
            default -> "UNCHANGED：不修改初始版本，验证裁判能够发现缺陷";
        };
        manifest(tasks, suite, root, config, provenance);
        List<EvalResult> results = new ArrayList<>();
        EvalRunner runner = new EvalRunner();
        // 固定题序、每次独立环境；所有尝试均写入结果，包括失败尝试。
        for (EvalTask task : tasks) for (int trial = 1; trial <= config.trials(); trial++) {
            EvalResult result = runner.run(task, trial, config, root);
            results.add(result);
            EvalFiles.json(root.resolve("results.json"), results);
            System.out.printf("%s [%d/%d] %s (%d ms)%n", task.id(), trial, config.trials(), result.status(), result.durationMs());
        }
        EvalReport.write(root, results, config.trials(), provenance);
        return results;
    }

    static void manifest(List<EvalTask> tasks, Path suite, Path root, EvalRunner.Config config, String provenance) throws Exception {
        Map<String, Object> data = EvalFiles.JSON.convertValue(config,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        data.put("schemaVersion", 1); data.put("startedAt", Instant.now().toString());
        data.put("provenance", provenance); data.put("java", System.getProperty("java.version"));
        data.put("os", System.getProperty("os.name")); data.put("suite", suite.toString());
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var task : tasks) hashes.put(task.id(), EvalFiles.sha256(suite.resolve(task.id() + ".json")));
        data.put("taskHashes", hashes);
        String classpath = EvalProcess.classpath();
        data.put("classpath", classpath);
        // 打包运行时记录 jar 指纹，比依赖可能含未提交修改的 Git HEAD 更准确。
        if (!classpath.contains(java.io.File.pathSeparator) && Files.isRegularFile(Path.of(classpath)))
            data.put("agentBinarySha256", EvalFiles.sha256(Path.of(classpath)));
        EvalFiles.json(root.resolve("run.json"), data);
    }

    private static EvalRunner.Config config(Map<String, String> options, String adapter, String profile, int trials) {
        String model = adapter.equals("live") ? options.getOrDefault("model", "deepseek-chat")
                : adapter.toUpperCase(java.util.Locale.ROOT) + "_NO_MODEL";
        return new EvalRunner.Config(adapter, profile, model, trials,
                integer(options, "timeout", 180), integer(options, "max-calls", 40),
                Long.parseLong(options.getOrDefault("max-tokens", "120000")), integer(options, "max-tools", 80), integer(options, "max-turns", 20));
    }
    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        var allowed = java.util.Set.of("suite", "split", "task", "out", "adapter", "profile", "trials", "timeout",
                "max-calls", "max-tokens", "max-tools", "max-turns", "model", "run", "baseline", "candidate");
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length || !allowed.contains(args[i].substring(2)))
                throw new IllegalArgumentException("Invalid option: " + args[i]);
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }
    private static int integer(Map<String, String> options, String key, int fallback) { return Integer.parseInt(options.getOrDefault(key, "" + fallback)); }
    private static String required(Map<String, String> options, String key) {
        if (!options.containsKey(key)) throw new IllegalArgumentException("Missing --" + key);
        return options.get(key);
    }
}
