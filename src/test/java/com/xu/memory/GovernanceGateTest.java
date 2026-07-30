package com.xu.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 治理门:去重 / 来源信任 / 置信挂起。 */
class GovernanceGateTest {

    private final GovernanceGate gate = new DefaultGovernanceGate();

    private MemoryRecord human(String content) {
        return MemoryRecord.create(content, MemoryScope.PROJECT, "/p", MemorySource.HUMAN, 0.9);
    }
    private MemoryRecord agent(String content, double conf) {
        return MemoryRecord.create(content, MemoryScope.PROJECT, "/p", MemorySource.AGENT, conf);
    }

    @Test
    void humanWriteShouldCommit() {
        assertEquals(GovernanceGate.Decision.COMMIT,
                gate.evaluate(human("这仓库跑测试要先设 JAVA_HOME"), List.of()));
    }

    @Test
    void exactDuplicateShouldReject() {
        MemoryRecord existing = human("用户偏好 Java 17");
        assertEquals(GovernanceGate.Decision.REJECT,
                gate.evaluate(human("用户偏好 Java 17"), List.of(existing)));
    }

    @Test
    void nearDuplicateShouldMerge() {
        // 同一组词、仅顺序不同 → token 重叠 1.0,但 content 不完全相等 → MERGE 而非 REJECT
        MemoryRecord existing = human("喜欢 简洁 注释 风格");
        assertEquals(GovernanceGate.Decision.MERGE,
                gate.evaluate(human("简洁 注释 风格 喜欢"), List.of(existing)));
    }

    @Test
    void agentHighConfidenceShouldCommit() {
        assertEquals(GovernanceGate.Decision.COMMIT,
                gate.evaluate(agent("mvn 首次跑测试不能加 -o", 0.8), List.of()));
    }

    @Test
    void agentLowConfidenceShouldDefer() {
        assertEquals(GovernanceGate.Decision.DEFER,
                gate.evaluate(agent("也许这个仓库用了某种缓存?", 0.4), List.of()));
    }
}
