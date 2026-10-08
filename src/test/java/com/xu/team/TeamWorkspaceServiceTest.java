package com.xu.team;

import com.xu.tool.ToolRegistry;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.xu.team.WorkspaceGit.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamWorkspaceServiceTest {
    @TempDir Path temp;

    private Path repository(List<String> commands) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        require(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("source.txt"), "base\n");
        if (commands != null) {
            Files.createDirectories(repo.resolve(".xcode"));
            Files.writeString(repo.resolve(".xcode/team-workflow.json"), TeamJson.write(Map.of("verificationCommands", commands)));
        }
        require(repo, "add", "."); require(repo, "commit", "-m", "base");
        return repo;
    }

    private static void write(TeamWorkspaceService.Workspace workspace, String file, String content) throws Exception {
        Files.writeString(Path.of(workspace.root()).resolve(file), content);
    }

    @Test void isolatedChangesCombineVerifyAndReviewWithoutTouchingUserCheckout() throws Exception {
        Path repo = repository(List.of("git ls-files --error-unmatch a.txt b.txt"));
        String original = head(repo);
        Files.writeString(repo.resolve("user-uncommitted.txt"), "user work");
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("flow"))) {
            var a = flow.prepare("a", List.of());
            var b = flow.prepare("b", List.of());
            assertFalse(Files.exists(Path.of(a.root()).resolve("user-uncommitted.txt")));
            write(a, "a.txt", "login"); write(b, "b.txt", "audit");
            assertFalse(Files.exists(Path.of(a.root()).resolve("b.txt")));
            flow.submit("a", "login"); flow.submit("b", "audit");
            var candidate = flow.build(List.of("a", "b"));
            assertEquals("login", Files.readString(Path.of(candidate.root()).resolve("a.txt")));
            assertEquals("audit", Files.readString(Path.of(candidate.root()).resolve("b.txt")));
            assertFalse(flow.finishBlocker().isEmpty());
            assertEquals("NOT_VERIFIED", assertThrows(TeamException.class,
                    () -> flow.review(candidate.id(), "reviewed")).code());
            assertEquals("VERIFIED", flow.verify(candidate.id(), new ToolRegistry(), new CancellationToken()).state());
            assertEquals("REVIEWED", flow.review(candidate.id(), "combined files reviewed").state());
            assertEquals("", flow.finishBlocker());
            assertEquals(original, head(repo));
            assertEquals("user work", Files.readString(repo.resolve("user-uncommitted.txt")));
            assertFalse(Files.exists(repo.resolve("a.txt")));
            assertTrue(Files.readString(flow.directory().resolve("workflow.json")).contains("REVIEWED"));
        }
    }

    @Test void dependencyRequiresFixedDeliveryAndBecomesPartOfChildBaseline() throws Exception {
        Path repo = repository(null);
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("flow"))) {
            var a = flow.prepare("a", List.of());
            assertEquals("DEPENDENCY_NOT_READY", assertThrows(TeamException.class,
                    () -> flow.checkDependencies(List.of("a"))).code());
            write(a, "api.txt", "contract-v2"); flow.submit("a", "api");
            var b = flow.prepare("b", List.of("a"));
            assertEquals("contract-v2", Files.readString(Path.of(b.root()).resolve("api.txt")));
            write(a, "api.txt", "unsubmitted");
            assertThrows(TeamException.class, () -> flow.checkDependencies(List.of("a")));
        }
    }

    @Test void textualConflictIsRetainedAndCannotBeReviewed() throws Exception {
        Path repo = repository(List.of("git status --porcelain"));
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("flow"))) {
            var a = flow.prepare("a", List.of()); var b = flow.prepare("b", List.of());
            write(a, "source.txt", "left\n"); write(b, "source.txt", "right\n");
            flow.submit("a", "left"); flow.submit("b", "right");
            assertEquals("MERGE_CONFLICT", assertThrows(TeamException.class,
                    () -> flow.build(List.of("a", "b"))).code());
            var json = TeamJson.MAPPER.readTree(Files.readString(flow.directory().resolve("workflow.json")));
            var candidate = json.get("candidates").get(0);
            assertEquals("CONFLICT_OR_FAILED", candidate.get("state").asText());
            assertFalse(require(Path.of(candidate.get("root").asText()), "ls-files", "-u").isEmpty());
            assertThrows(TeamException.class, () -> flow.verify(candidate.get("id").asText(), new ToolRegistry(), new CancellationToken()));
            assertEquals("base\n", Files.readString(repo.resolve("source.txt")));
        }
    }

    @Test void targetAndInputDriftInvalidateReviewAndFinalAcceptance() throws Exception {
        Path repo = repository(List.of("git status --porcelain"));
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("flow"))) {
            var a = flow.prepare("a", List.of()); write(a, "a.txt", "one"); flow.submit("a", "one");
            var c = flow.build(List.of("a")); flow.verify(c.id(), new ToolRegistry(), new CancellationToken());
            flow.review(c.id(), "reviewed");
            flow.invalidate("a");
            assertFalse(flow.finishBlocker().isEmpty());
            flow.submit("a", "same");
            assertEquals("", flow.finishBlocker());
            write(a, "a.txt", "two"); flow.submit("a", "two");
            assertEquals("STALE_DELIVERY", assertThrows(TeamException.class, () -> flow.review(c.id(), "again")).code());
            var fresh = flow.build(List.of("a")); flow.verify(fresh.id(), new ToolRegistry(), new CancellationToken());
            flow.review(fresh.id(), "new candidate reviewed");
            assertEquals("", flow.finishBlocker(), "过期旧候选不能遮挡新的有效候选");
            require(repo, "commit", "--allow-empty", "-m", "main advanced");
            assertEquals("STALE_TARGET", assertThrows(TeamException.class, () -> flow.review(fresh.id(), "review")).code());
        }
    }

    @Test void verificationCannotClaimSuccessWithoutPolicyOrOnFailedChecks() throws Exception {
        Path repo = repository(null);
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("no-policy"))) {
            flow.prepare("a", List.of()); flow.submit("a", "unchanged");
            var c = flow.build(List.of("a"));
            assertEquals("NO_VERIFICATION_POLICY", assertThrows(TeamException.class,
                    () -> flow.verify(c.id(), new ToolRegistry(), new CancellationToken())).code());
            flow.defer("a", "verification policy not configured");
            assertTrue(flow.hasDeferred()); assertEquals("", flow.finishBlocker());
        }
        Files.createDirectories(repo.resolve(".xcode"));
        Files.writeString(repo.resolve(".xcode/team-workflow.json"), TeamJson.write(Map.of("verificationCommands", List.of("git ls-files --error-unmatch missing.txt"))));
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("failing-check"))) {
            flow.prepare("a", List.of()); flow.submit("a", "unchanged");
            var c = flow.build(List.of("a"));
            assertEquals("CHECKS_FAILED", flow.verify(c.id(), new ToolRegistry(), new CancellationToken()).state());
            assertThrows(TeamException.class, () -> flow.review(c.id(), "cannot approve"));
        }
    }

    @Test void ownerLockAndRecoveryRecordPreventConcurrentOrBlindReopening() throws Exception {
        Path repo = repository(null), data = temp.resolve("flow");
        try (var flow = new TeamWorkspaceService(repo, data)) {
            assertEquals("WORKSPACE_BUSY", assertThrows(TeamException.class, () -> new TeamWorkspaceService(repo, data)).code());
        }
        assertEquals("RECOVERY_REQUIRED", assertThrows(TeamException.class, () -> new TeamWorkspaceService(repo, data)).code());
    }

    @Test void verificationThatChangesCommitIsRejectedAndJournaled() throws Exception {
        Path repo = repository(List.of("git -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit --allow-empty -m changed"));
        try (var flow = new TeamWorkspaceService(repo, temp.resolve("flow"))) {
            flow.prepare("a", List.of()); flow.submit("a", "unchanged");
            var c = flow.build(List.of("a"));
            assertEquals("STALE_VERIFICATION", assertThrows(TeamException.class,
                    () -> flow.verify(c.id(), new ToolRegistry(), new CancellationToken())).code());
            assertEquals("CHECKS_FAILED", flow.candidate(c.id()).state());
            assertThrows(TeamException.class, () -> flow.review(c.id(), "not allowed"));
        }
    }
}
