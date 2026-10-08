package com.xu.team;

import com.xu.tool.*;
import com.xu.util.CancellationToken;
import com.xu.util.FileUtils;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.xu.team.WorkspaceGit.*;

/** 隔离开发 → 固定交付 → 候选集成 → 验证/审查。永不自动移动用户分支或删除现场。 */
public final class TeamWorkspaceService implements AutoCloseable {
    public record Workspace(String agentId, String attemptId, String root, String branch,
                            String baseCommit, List<String> dependencies) {}
    public record Change(String agentId, String commit, String summary) {}
    public record Check(String command, String commit, ToolExecutionResult result) {}
    public record Candidate(String id, String root, String branch, String targetRef, String targetCommit,
                            List<Change> inputs, String commit, String state, List<Check> checks, String review) {}

    private final Path project, directory;
    private final List<String> verificationCommands;
    private final int verificationTimeoutSeconds;
    private final Lease lease;
    private final Map<String, Workspace> workspaces = new LinkedHashMap<>();
    private final Map<String, Change> changes = new LinkedHashMap<>();
    private final Map<String, String> deferred = new LinkedHashMap<>();
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private String failure = "";

    public TeamWorkspaceService(Path project, Path directory) throws IOException {
        this.project = project.toRealPath();
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        Policy policy = loadPolicy(project.resolve(".xcode/team-workflow.json"));
        verificationCommands = policy.commands();
        verificationTimeoutSeconds = policy.timeoutSeconds();
        lease = lock(this.directory.resolve("owner.lock"));
        if (Files.exists(this.directory.resolve("workflow.json"))) {
            lease.close();
            throw new TeamException("RECOVERY_REQUIRED", "已有工作流记录；请核对现场，不自动重放: " + directory);
        }
        try { save(); } catch (IOException e) { lease.close(); throw e; }
    }

    private record Policy(List<String> commands, int timeoutSeconds) {}
    private static Policy loadPolicy(Path file) throws IOException {
        if (!Files.exists(file)) return new Policy(List.of(), 600);
        if (Files.size(file) > 32768) throw new IOException("team-workflow.json 超过 32KB");
        var policy = TeamJson.MAPPER.readTree(Files.readString(file));
        if (policy == null || !policy.isObject()) throw new IOException("team-workflow.json 必须为对象");
        var value = policy.get("verificationCommands");
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 16)
            throw new IOException("verificationCommands 必须包含 1..16 个检查命令");
        List<String> commands = new ArrayList<>();
        for (var item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 4096)
                throw new IOException("验证命令必须为非空字符串，最多 4096 字符");
            commands.add(item.asText());
        }
        var timeout = policy.get("verificationTimeoutSeconds");
        if (timeout != null && (!timeout.isIntegralNumber() || timeout.asLong() < 1 || timeout.asLong() > 3600))
            throw new IOException("verificationTimeoutSeconds 必须为 1..3600");
        return new Policy(List.copyOf(commands), timeout == null ? 600 : timeout.asInt());
    }

    public synchronized void checkDependencies(List<String> ids) throws Exception {
        healthy();
        for (String id : ids) currentChange(id);
    }

    public synchronized Workspace prepare(String agentId, List<String> dependencies) throws Exception {
        healthy();
        if (workspaces.containsKey(agentId)) return workspaces.get(agentId);
        List<Change> inputs = new ArrayList<>();
        for (String id : dependencies) inputs.add(currentChange(id));
        String attempt = UUID.randomUUID().toString();
        Path root = directory.resolve("worktrees").resolve(attempt);
        String branch = "codex/task-" + attempt;
        String base = head(project);
        Workspace workspace = new Workspace(agentId, attempt, root.toString(), branch, base, List.copyOf(dependencies));
        workspaces.put(agentId, workspace);
        save(); // 先登记意图；创建中断也能找到需要核对的现场。
        create(root, branch, base);
        for (Change input : inputs) merge(root, input.commit());
        workspace = new Workspace(agentId, attempt, root.toString(), branch, head(root), List.copyOf(dependencies));
        workspaces.put(agentId, workspace);
        save();
        return workspace;
    }

    private void create(Path root, String branch, String base) throws Exception {
        Files.createDirectories(root.getParent());
        Path common = Path.of(require(project, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath();
        try (Lease ignored = lock(common.resolve("xcode-worktree.lock"))) {
            require(project, "worktree", "add", "-b", branch, root.toString(), base);
        }
    }

    private static void merge(Path root, String commit) throws Exception {
        Result result = run(root, "merge", "--no-edit", "--no-ff", commit);
        if (result.exitCode() != 0)
            throw new TeamException("MERGE_CONFLICT", "候选现场已保留: " + root + "\n" + result.output());
    }

    public synchronized Workspace workspace(String agentId) {
        Workspace workspace = workspaces.get(agentId);
        if (workspace == null) throw new TeamException("UNKNOWN_WORKSPACE", "成员没有独立写工作区");
        return workspace;
    }

    public synchronized Map<String, String> environment(String agentId) throws IOException {
        Workspace workspace = workspace(agentId);
        Path temp = directory.resolve("temporary").resolve(workspace.attemptId());
        Files.createDirectories(temp);
        return Map.of("XCODE_TASK_ID", agentId, "XCODE_ATTEMPT_ID", workspace.attemptId(),
                "XCODE_RESOURCE_NAMESPACE", workspace.attemptId(), "TMPDIR", temp.toString(),
                "TMP", temp.toString(), "TEMP", temp.toString());
    }

    public synchronized void invalidate(String agentId) throws IOException {
        healthy();
        boolean changed = changes.remove(agentId) != null;
        changed |= deferred.remove(agentId) != null;
        if (changed) save();
    }

    public synchronized Change submit(String agentId, String summary) throws Exception {
        healthy();
        TeamTypes.text(summary, "summary", 4096);
        Workspace workspace = workspace(agentId);
        Path root = Path.of(workspace.root());
        requireBranch(root, workspace.branch());
        require(root, "merge-base", "--is-ancestor", workspace.baseCommit(), "HEAD");
        if (!require(root, "ls-files", "-u").isEmpty())
            throw new TeamException("MERGE_CONFLICT", "先解决工作区冲突");
        require(root, "add", "--all");
        if (run(root, "diff", "--cached", "--quiet").exitCode() != 0)
            require(root, "commit", "-m", summary);
        clean(root);
        Change change = new Change(agentId, head(root), summary);
        changes.put(agentId, change);
        deferred.remove(agentId);
        save();
        return change;
    }

    private Change currentChange(String id) throws Exception {
        Change change = changes.get(id);
        if (change == null || deferred.containsKey(id))
            throw new TeamException("DEPENDENCY_NOT_READY", "成员尚未交付有效提交: " + id);
        Workspace workspace = workspace(id);
        Path root = Path.of(workspace.root());
        requireBranch(root, workspace.branch());
        clean(root);
        if (!head(root).equals(change.commit()))
            throw new TeamException("STALE_DELIVERY", "成员代码已变化，需要重新交付: " + id);
        return change;
    }

    public synchronized Candidate build(List<String> ids) throws Exception {
        healthy();
        if (candidates.size() >= 32) throw new TeamException("TEAM_CAPACITY_EXCEEDED", "候选创建次数已达 32 次，请检查已有现场");
        if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size())
            throw new TeamException("INVALID_ARGUMENT", "agentIds 必须非空且不能重复");
        List<Change> inputs = new ArrayList<>();
        for (String id : ids) inputs.add(currentChange(id));
        String target = head(project);
        Result symbolic = run(project, "symbolic-ref", "-q", "HEAD");
        String targetRef = symbolic.exitCode() == 0 ? symbolic.output() : target;
        String id = UUID.randomUUID().toString();
        Path root = directory.resolve("integrations").resolve(id);
        Candidate candidate = new Candidate(id, root.toString(), "codex/integration-" + id,
                targetRef, target, List.copyOf(inputs), "", "PREPARING", List.of(), "");
        candidates.put(id, candidate); save();
        try {
            create(root, candidate.branch(), target);
            for (Change input : inputs) merge(root, input.commit());
            candidate = replace(candidate, head(root), "READY", List.of(), "");
        } catch (Exception error) {
            candidates.put(id, replace(candidate, "", "CONFLICT_OR_FAILED", List.of(), error.getMessage()));
            save();
            throw error;
        }
        candidates.put(id, candidate); save();
        return candidate;
    }

    public synchronized Candidate candidate(String id) {
        Candidate candidate = candidates.get(id);
        if (candidate == null) throw new TeamException("UNKNOWN_CANDIDATE", "候选不存在");
        return candidate;
    }

    public synchronized Map<String, String> candidateEnvironment(String id) throws IOException {
        candidate(id);
        Path temp = directory.resolve("temporary").resolve(id);
        Files.createDirectories(temp);
        return Map.of("XCODE_CANDIDATE_ID", id, "XCODE_RESOURCE_NAMESPACE", id,
                "TMPDIR", temp.toString(), "TMP", temp.toString(), "TEMP", temp.toString());
    }

    public synchronized void candidateChanging(String id) throws IOException {
        healthy();
        Candidate candidate = candidate(id);
        candidates.put(id, replace(candidate, "", "NEEDS_VERIFICATION", List.of(), ""));
        save();
    }

    public synchronized Candidate verify(String id, ToolRegistry tools, CancellationToken token) throws Exception {
        healthy();
        if (verificationCommands.isEmpty())
            throw new TeamException("NO_VERIFICATION_POLICY", "启动团队前配置 .xcode/team-workflow.json 的 verificationCommands；不能用模型声明代替检查");
        Candidate candidate = candidate(id);
        Path root = Path.of(candidate.root());
        requireBranch(root, candidate.branch()); clean(root);
        String commit = head(root);
        for (Change input : candidate.inputs()) require(root, "merge-base", "--is-ancestor", input.commit(), commit);
        List<Check> checks = new ArrayList<>();
        candidates.put(id, replace(candidate, commit, "VERIFYING", checks, "")); save();
        try {
            Tool executor = tools.forWorkspace(root, candidateEnvironment(id), token, verificationTimeoutSeconds).get("execute_command");
            for (String command : verificationCommands) {
                token.throwIfCancellationRequested();
                ToolExecutionResult result = executor.executeObserved(Map.of("command", command));
                checks.add(new Check(command, commit, result));
                if (!result.success()) break;
            }
            clean(root);
            if (!head(root).equals(commit)) throw new TeamException("STALE_VERIFICATION", "验证命令改变了候选提交");
            boolean passed = checks.size() == verificationCommands.size() && checks.stream().allMatch(c -> c.result().success());
            candidate = replace(candidate, commit, passed ? "VERIFIED" : "CHECKS_FAILED", List.copyOf(checks), "");
        } catch (Exception e) {
            candidates.put(id, replace(candidate, commit, "CHECKS_FAILED", List.copyOf(checks), e.getMessage()));
            save(); throw e;
        }
        candidates.put(id, candidate); save();
        return candidate;
    }

    public synchronized Candidate review(String id, String evidence) throws Exception {
        healthy();
        TeamTypes.text(evidence, "evidence", 8192);
        Candidate candidate = candidate(id);
        if (!Set.of("VERIFIED", "REVIEWED").contains(candidate.state()))
            throw new TeamException("NOT_VERIFIED", "候选必须先通过配置的检查");
        validate(candidate);
        candidate = replace(candidate, candidate.commit(), "REVIEWED", candidate.checks(), evidence);
        candidates.put(id, candidate); save();
        return candidate;
    }

    private void validate(Candidate candidate) throws Exception {
        Path root = Path.of(candidate.root());
        requireBranch(root, candidate.branch()); clean(root);
        if (!head(root).equals(candidate.commit())) throw new TeamException("STALE_VERIFICATION", "候选已变化，重新验证");
        String target = require(project, "rev-parse", "--verify", "--end-of-options", candidate.targetRef() + "^{commit}");
        if (!target.equals(candidate.targetCommit())) throw new TeamException("STALE_TARGET", "目标分支已前进，重新构建候选");
        for (Change input : candidate.inputs())
            if (!currentChange(input.agentId()).commit().equals(input.commit()))
                throw new TeamException("STALE_DELIVERY", "输入成果已更新，重新构建候选");
    }

    public synchronized void defer(String agentId, String reason) throws IOException {
        healthy(); workspace(agentId); TeamTypes.text(reason, "reason", 4096);
        deferred.put(agentId, reason); save();
    }

    public synchronized String workerBlocker(String agentId) {
        try { currentChange(agentId); return ""; }
        catch (Exception e) { return "使用 submit_changes 交付确定版本后才能完成：" + e.getMessage(); }
    }

    public synchronized String finishBlocker() {
        try {
            healthy();
            Set<String> required = new HashSet<>(workspaces.keySet()); required.removeAll(deferred.keySet());
            if (required.isEmpty()) return "";
            String stale = "";
            // 必须有同一个已审查候选覆盖全部成果，不能用各自通过替代组合验收。
            for (Candidate candidate : candidates.values()) {
                if (!candidate.state().equals("REVIEWED")) continue;
                if (!candidate.inputs().stream().map(Change::agentId).collect(java.util.stream.Collectors.toSet()).containsAll(required)) continue;
                try { validate(candidate); return ""; }
                catch (Exception e) { stale = e.getMessage(); }
            }
            if (!stale.isEmpty()) return stale;
            return "写任务需要统一构建候选、验证并 review_integration；不交付的任务用 defer_changes 说明原因";
        } catch (Exception e) { return e.getMessage(); }
    }

    public synchronized boolean hasDeferred() { return !deferred.isEmpty(); }
    public synchronized Object snapshot() {
        return Map.of("workspaces", List.copyOf(workspaces.values()), "deliveries", List.copyOf(changes.values()),
                "candidates", List.copyOf(candidates.values()), "deferred", Map.copyOf(deferred),
                "verificationCommands", verificationCommands, "verificationTimeoutSeconds", verificationTimeoutSeconds,
                "automaticResume", false, "failure", failure);
    }
    public Path directory() { return directory; }

    private static void requireBranch(Path root, String branch) throws Exception {
        if (!require(root, "symbolic-ref", "--short", "HEAD").equals(branch))
            throw new TeamException("BRANCH_CHANGED", "任务分支被切换，先核对现场");
    }
    private static Candidate replace(Candidate c, String commit, String state, List<Check> checks, String review) {
        return new Candidate(c.id(), c.root(), c.branch(), c.targetRef(), c.targetCommit(), c.inputs(), commit, state, List.copyOf(checks), review);
    }
    private void healthy() {
        if (!failure.isEmpty()) throw new TeamException("JOURNAL_FAILED", failure);
    }
    private void save() throws IOException {
        try { FileUtils.atomicWrite(directory.resolve("workflow.json"), com.xu.ui.SafeDisplay.redact(TeamJson.write(snapshot()))); }
        catch (IOException e) { failure = "工作流记录失败；停止修改并保留现场: " + e.getClass().getSimpleName(); throw e; }
    }
    @Override public synchronized void close() throws IOException { lease.close(); }
}
