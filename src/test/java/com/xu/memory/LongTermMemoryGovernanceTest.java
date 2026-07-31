package com.xu.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 统一写入口中的去重、来源信任和低置信挂起规则。 */
class LongTermMemoryGovernanceTest {

    private MemoryRecord human(String content) {
        return MemoryRecord.create(content, MemoryScope.PROJECT, "/p", MemorySource.HUMAN, 0.9);
    }
    private MemoryRecord agent(String content, double conf) {
        return MemoryRecord.create(content, MemoryScope.PROJECT, "/p", MemorySource.AGENT, conf);
    }

    @Test
    void humanWriteShouldCommit() {
        LongTermMemory memory = LongTermMemory.inMemory();
        assertEquals(LongTermMemory.SaveResult.COMMIT,
                memory.save(human("这仓库跑测试要先设 JAVA_HOME")));
    }

    @Test
    void exactDuplicateShouldReject() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(human("用户偏好 Java 17"));
        assertEquals(LongTermMemory.SaveResult.REJECT,
                memory.save(human("用户偏好 Java 17")));
    }

    @Test
    void nearDuplicateShouldMerge() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(human("喜欢 简洁 注释 风格"));
        assertEquals(LongTermMemory.SaveResult.MERGE,
                memory.save(human("简洁 注释 风格 喜欢")));
    }

    @Test
    void agentHighConfidenceShouldCommit() {
        LongTermMemory memory = LongTermMemory.inMemory();
        assertEquals(LongTermMemory.SaveResult.COMMIT,
                memory.save(agent("mvn 首次跑测试不能加 -o", 0.8)));
    }

    @Test
    void agentLowConfidenceShouldDefer() {
        LongTermMemory memory = LongTermMemory.inMemory();
        assertEquals(LongTermMemory.SaveResult.DEFER,
                memory.save(agent("也许这个仓库用了某种缓存?", 0.4)));
    }

    @Test
    void lowConfidenceDuplicateShouldMergeInsteadOfQueueing() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(human("喜欢 简洁 注释 风格"));

        assertEquals(LongTermMemory.SaveResult.MERGE,
                memory.save(agent("简洁 注释 风格 喜欢", 0.4)));
        assertTrue(memory.pendingReview().isEmpty());
    }
}
