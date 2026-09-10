package com.xu.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EvalReportTest {
    private EvalResult result(String task, int trial, String status, long millis) {
        return new EvalResult(task, task, "BUG", trial, "scripted", "react", "fake", status,
                "SUCCESS", millis, 20, 100, 10, true, 1, 1, false, Map.of(), "evidence", "");
    }
    @Test void distinguishesAnySuccessFromConsistencyAndKeepsFailuresInLatency() {
        var summary = EvalReport.summarize(List.of(result("a",1,"PASS",10),result("a",2,"PASS",20),result("a",3,"PASS",30),
                result("b",1,"PASS",40),result("b",2,"TASK_FAILED",50),result("b",3,"TIMEOUT",180000)),3);
        assertEquals(2.0/3,summary.pass1(),.0001);
        assertEquals(1,summary.anySuccess()); assertEquals(.5,summary.allSuccess());
        assertEquals(180000,summary.p95Ms()); assertEquals(4,summary.passed());
    }
    @Test void excludesInfrastructureFromQualityAndIncompleteRepeatGroups() {
        var summary=EvalReport.summarize(List.of(result("a",1,"PASS",10),result("a",2,"INFRA_ERROR",20),
                result("b",1,"TASK_FAILED",30),result("b",2,"TASK_FAILED",40)),2);
        assertEquals(.5,summary.pass1());assertEquals(1,summary.infraErrors());
        assertEquals(1,summary.completeRepeatGroups());assertEquals(0,summary.anySuccess());
    }
    @Test void weightsTasksEquallyInsteadOfRewardingRepeatedEasyTasks() {
        var summary=EvalReport.summarize(List.of(result("easy",1,"PASS",10),result("easy",2,"PASS",10),
                result("hard",1,"TASK_FAILED",10)),2);
        assertEquals(.5,summary.pass1());
    }
    @Test void repeatedTrialIdsDoNotMasqueradeAsIndependentAttempts() {
        var summary=EvalReport.summarize(List.of(result("a",1,"PASS",10),result("a",1,"PASS",10)),2);
        assertEquals(0,summary.completeRepeatGroups());assertNull(summary.allSuccess());
    }
}
