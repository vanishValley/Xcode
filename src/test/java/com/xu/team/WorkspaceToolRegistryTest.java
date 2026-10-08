package com.xu.team;

import com.xu.hitl.*;
import com.xu.tool.*;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceToolRegistryTest {
    @TempDir Path root;

    @Test void forkRetainsApprovalAndShowsActualWorkspaceBeforeWriting() throws Exception {
        AtomicReference<Map<String, Object>> observed = new AtomicReference<>();
        HitlHandler handler = new HitlHandler() {
            @Override public ApprovalResult requestApproval(String name, Map<String, Object> arguments) {
                observed.set(arguments);
                return new ApprovalResult(ApprovalResult.Type.REJECTED, "denied");
            }
            @Override public void clearSessionState() {}
        };
        HitlToolRegistry base = new HitlToolRegistry(handler); base.setEnabled(true);
        ToolRegistry scoped = base.forWorkspace(root, Map.of(), new CancellationToken());
        var result = scoped.get("write_file").executeObserved(Map.of("path", "test.txt", "content", "no"));
        assertFalse(result.success()); assertFalse(Files.exists(root.resolve("test.txt")));
        assertEquals(root.toAbsolutePath().normalize().toString(), observed.get().get("workspace"));
        assertNull(scoped.get("mcp__external"));
    }

    @Test void shellAndFilesUseTaskDirectoryAndNamespace() throws Exception {
        ToolRegistry scoped = new ToolRegistry().forWorkspace(root, Map.of("XCODE_TASK_ID", "task-42"), new CancellationToken());
        assertTrue(scoped.get("write_file").executeObserved(Map.of("path", "unique.txt", "content", "task-only")).success());
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "type unique.txt && echo %XCODE_TASK_ID%" : "cat unique.txt && printenv XCODE_TASK_ID";
        var result = scoped.get("execute_command").executeObserved(Map.of("command", command));
        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("task-only")); assertTrue(result.content().contains("task-42"));
        assertFalse(scoped.get("write_file").executeObserved(Map.of("path", "../escape.txt", "content", "no")).success());
    }
}
