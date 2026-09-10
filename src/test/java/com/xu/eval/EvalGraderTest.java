package com.xu.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EvalGraderTest {
    @TempDir Path root;
    private EvalTask task() throws Exception {
        return EvalMain.load(Path.of("benchmarks/java-v1/tasks").toAbsolutePath(),Map.of("task","bug-01-pagination")).get(0);
    }
    @Test void unchangedCodePassesVisibleRegressionButFailsHiddenBehavior() throws Exception {
        var task=task();var work=root.resolve("work");EvalFiles.writeFiles(work,task.files());
        var grade=new EvalGrader().grade(task,work,root.resolve("verify"));
        assertEquals("TASK_FAILED",grade.status());assertTrue(grade.checks().get("RegressionCheck"));
    }
    @Test void referencePassesWithIndependentCompilation() throws Exception {
        var task=task();var work=root.resolve("work");EvalFiles.writeFiles(work,task.files());EvalFiles.writeFiles(work,task.reference());
        var grade=new EvalGrader().grade(task,work,root.resolve("verify"));
        assertEquals("PASS",grade.status());assertFalse(Files.exists(work.resolve("BehaviorCheck.java")));
    }
    @Test void modifyingVisibleTestsDoesNotChangeExternalVerdict() throws Exception {
        var task=task();var work=root.resolve("work");EvalFiles.writeFiles(work,task.files());EvalFiles.writeFiles(work,task.reference());
        Files.writeString(work.resolve("VisibleCheck.java"),"public class VisibleCheck {public static void main(String[] args){}}\n");
        var grade=new EvalGrader().grade(task,work,root.resolve("verify"));
        assertEquals("CONSTRAINT_FAILED",grade.status());assertTrue(grade.checks().get("BehaviorCheck"));
    }
    @Test void compileFailureIsAnAgentFailureNotInfrastructureNoise() throws Exception {
        var task=task();var work=root.resolve("work");EvalFiles.writeFiles(work,task.files());Files.writeString(work.resolve("Pager.java"),"invalid Java");
        var grade=new EvalGrader().grade(task,work,root.resolve("verify"));assertEquals("COMPILE_FAILED",grade.status());
    }
    @Test void rejectsTaskPathsOutsideWorkspace() {
        assertThrows(IllegalArgumentException.class,()->EvalFiles.resolve(root,"../outside.java"));
    }
}
