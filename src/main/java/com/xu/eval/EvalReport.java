package com.xu.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/** 所有口径集中在这里。任务等权，重复运行按任务分组，不冒充更多独立题目。 */
public final class EvalReport {
    public record Summary(int tasks, int trials, int eligible, int infraErrors, int passed,
                          double pass1, Double anySuccess, Double allSuccess,
                          int completeRepeatGroups, long p50Ms, long p95Ms,
                          long inputTokens, long outputTokens, int incompleteUsage,
                          Map<String, Long> statuses, Map<String, Double> categoryPass1) {}

    public static Summary summarize(List<EvalResult> results, int repeats) {
        var byTask = results.stream().collect(Collectors.groupingBy(EvalResult::taskId));
        double pass1 = byTask.values().stream().filter(g -> g.stream().anyMatch(EvalResult::eligible))
                .mapToDouble(EvalReport::rate).average().orElse(0);
        var complete = byTask.values().stream().filter(g -> g.size() == repeats
                && g.stream().map(EvalResult::trial).distinct().count() == repeats
                && g.stream().allMatch(EvalResult::eligible)).toList();
        Double any = complete.isEmpty() ? null : complete.stream().filter(g -> g.stream().anyMatch(EvalResult::passed)).count() / (double) complete.size();
        Double all = complete.isEmpty() ? null : complete.stream().filter(g -> g.stream().allMatch(EvalResult::passed)).count() / (double) complete.size();
        var byCategory = results.stream().collect(Collectors.groupingBy(EvalResult::category));
        Map<String, Double> categoryRates = new java.util.TreeMap<>();
        byCategory.forEach((name, group) -> categoryRates.put(name, group.stream()
                .collect(Collectors.groupingBy(EvalResult::taskId)).values().stream()
                .filter(g -> g.stream().anyMatch(EvalResult::eligible)).mapToDouble(EvalReport::rate).average().orElse(0)));
        var times = results.stream().filter(EvalResult::eligible).map(EvalResult::durationMs).sorted().toList();
        return new Summary(byTask.size(), results.size(), (int) results.stream().filter(EvalResult::eligible).count(),
                (int) results.stream().filter(r -> !r.eligible()).count(), (int) results.stream().filter(EvalResult::passed).count(),
                pass1, any, all, complete.size(), percentile(times, .5), percentile(times, .95),
                results.stream().mapToLong(EvalResult::inputTokens).sum(), results.stream().mapToLong(EvalResult::outputTokens).sum(),
                (int) results.stream().filter(r -> !r.usageComplete()).count(),
                results.stream().collect(Collectors.groupingBy(EvalResult::status, java.util.TreeMap::new, Collectors.counting())), categoryRates);
    }
    private static double rate(List<EvalResult> group) {
        long eligible = group.stream().filter(EvalResult::eligible).count();
        return eligible == 0 ? 0 : group.stream().filter(EvalResult::passed).count() / (double) eligible;
    }
    private static long percentile(List<Long> values, double p) {
        return values.isEmpty() ? 0 : values.get(Math.max(0, (int) Math.ceil(p * values.size()) - 1));
    }
    static String percent(Double rate) { return rate == null ? "N/A" : String.format(Locale.ROOT, "%.1f%%", rate * 100); }

    public static void write(Path root, List<EvalResult> results, int repeats, String provenance) throws Exception {
        Summary summary = summarize(results, repeats);
        EvalFiles.json(root.resolve("results.json"), results);
        EvalFiles.json(root.resolve("summary.json"), summary);
        StringBuilder report = new StringBuilder("# Xcode Agent 评测报告\n\n数据来源：**" + provenance + "**\n\n");
        report.append("| 指标 | 结果 |\n|---|---:|\n")
                .append("| 题目 / 尝试 | ").append(summary.tasks()).append(" / ").append(summary.trials()).append(" |\n")
                .append("| 通过尝试 / 有效尝试 | ").append(summary.passed()).append(" / ").append(summary.eligible()).append(" |\n")
                .append("| 平均单次通过率（题目等权） | ").append(percent(summary.pass1())).append(" |\n")
                .append("| 至少一次成功 / 每次都成功（").append(repeats).append(" 次） | ")
                .append(percent(summary.anySuccess())).append(" / ").append(percent(summary.allSuccess())).append(" |\n")
                .append("| 完整重复组数 | ").append(summary.completeRepeatGroups()).append(" |\n")
                .append("| Agent 耗时 P50 / P95 | ").append(summary.p50Ms()).append(" / ").append(summary.p95Ms()).append(" ms |\n")
                .append("| 已知输入 / 输出 token | ").append(summary.inputTokens()).append(" / ").append(summary.outputTokens()).append(" |\n")
                .append("| 用量不完整尝试 | ").append(summary.incompleteUsage()).append(" |\n")
                .append("| 基础设施失败 | ").append(summary.infraErrors()).append(" |\n\n")
                .append("耗时包含失败和超时，不含独立验收耗时；token 包含失败尝试已知用量。缺失用量不等于免费。")
                .append("本报告不自动换算货币；缓存折扣、价格版本与未知用量未统一时，避免生成虚假的精确费用。\n\n")
                .append("## 分类与失败\n\n| 分类 | 单次通过率 |\n|---|---:|\n");
        summary.categoryPass1().forEach((category, value) -> report.append("| ").append(category).append(" | ").append(percent(value)).append(" |\n"));
        report.append("\n状态分布：").append(summary.statuses()).append("\n\n## 尝试明细\n\n| 任务 | 次数 | 验收状态 | 执行状态 | 证据 |\n|---|---:|---|---|---|\n");
        for (var result : results) report.append("| ").append(result.taskId()).append(" | ").append(result.trial())
                .append(" | ").append(result.status()).append(" | ").append(result.runtimeStatus()).append(" | [result](")
                .append(result.evidenceDir()).append("/result.json) |\n");
        Files.writeString(root.resolve("report.md"), report);
    }

    public static void compare(Path baseline, Path candidate) throws Exception {
        var leftManifest = EvalFiles.JSON.readTree(baseline.resolve("run.json").toFile());
        var rightManifest = EvalFiles.JSON.readTree(candidate.resolve("run.json").toFile());
        for (String field : List.of("taskHashes", "adapter", "model", "trials", "maxCalls", "maxTokens", "maxTools", "maxTurns", "timeoutSeconds"))
            if (!leftManifest.path(field).equals(rightManifest.path(field)))
                throw new IllegalArgumentException("Comparison configuration differs: " + field);
        List<EvalResult> before = read(baseline), after = read(candidate);
        Map<String, EvalResult> old = before.stream().collect(Collectors.toMap(r -> r.taskId() + ":" + r.trial(), r -> r));
        List<EvalResult> pairedBefore = new ArrayList<>(), pairedAfter = new ArrayList<>();
        int gains = 0, losses = 0, excluded = 0;
        for (var result : after) {
            var previous = old.get(result.taskId() + ":" + result.trial());
            if (previous == null || !previous.eligible() || !result.eligible()) { excluded++; continue; }
            pairedBefore.add(previous); pairedAfter.add(result);
            if (!previous.passed() && result.passed()) gains++;
            if (previous.passed() && !result.passed()) losses++;
        }
        if (pairedBefore.isEmpty()) throw new IllegalArgumentException("No valid paired trials");
        var a = pairedBefore.stream().collect(Collectors.groupingBy(EvalResult::taskId));
        var b = pairedAfter.stream().collect(Collectors.groupingBy(EvalResult::taskId));
        var ids = a.keySet().stream().sorted().toList();
        double[] deltas = ids.stream().mapToDouble(id -> rate(b.get(id)) - rate(a.get(id))).toArray();
        double mean = java.util.Arrays.stream(deltas).average().orElse(0);
        // 按题目重采样：同一题的多次尝试不当成独立的新题。
        Random random = new Random(20260910);
        double[] samples = new double[2000];
        for (int i = 0; i < samples.length; i++) {
            for (int j = 0; j < deltas.length; j++) samples[i] += deltas[random.nextInt(deltas.length)];
            samples[i] /= deltas.length;
        }
        java.util.Arrays.sort(samples);
        String report = "# 配对对照报告\n\n基线：" + baseline + "\n\n候选：" + candidate
                + "\n\n数据来源：" + rightManifest.path("provenance").asText()
                + "\n\n有效配对：" + pairedBefore.size() + "；排除：" + excluded
                + "；失败→成功：" + gains + "；成功→失败：" + losses
                + String.format(Locale.ROOT, "\n\n题目等权通过率差：%+.1f 个百分点；按题目 bootstrap 95%% 区间：[%.1f, %.1f] 个百分点。\n", mean * 100, samples[49] * 100, samples[1949] * 100)
                + "\n区间是小样本描述性估计；同源题家族相关性可能让区间偏窄，不作为因果或显著性保证。\n";
        Files.writeString(candidate.resolve("comparison.md"), report);
    }
    public static List<EvalResult> read(Path root) throws Exception {
        return EvalFiles.JSON.readValue(root.resolve("results.json").toFile(),
                EvalFiles.JSON.getTypeFactory().constructCollectionType(List.class, EvalResult.class));
    }
}
