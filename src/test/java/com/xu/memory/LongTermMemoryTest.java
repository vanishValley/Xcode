package com.xu.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 长期记忆的写入、审核和检索端到端测试。 */
class LongTermMemoryTest {

    private LongTermMemory.SaveResult saveHuman(
            LongTermMemory memory,
            String content,
            MemoryScope scope,
            String projectKey) {
        return memory.save(MemoryRecord.create(
                content,
                scope,
                projectKey,
                MemorySource.HUMAN,
                0.9));
    }

    @Test
    void humanSaveShouldCommitAndBeRetrievable() {
        LongTermMemory memory = LongTermMemory.inMemory();
        LongTermMemory.SaveResult result = saveHuman(
                memory,
                "升级 okhttp 到 5.0",
                MemoryScope.PROJECT,
                "/p");

        assertEquals(LongTermMemory.SaveResult.COMMIT, result);
        assertEquals(1, memory.list("/p").size());
        assertFalse(memory.retrieve("帮我升级 okhttp", "/p").isEmpty());
    }

    @Test
    void duplicateSaveShouldNotGrowStore() {
        LongTermMemory memory = LongTermMemory.inMemory();
        saveHuman(
                memory,
                "用户偏好 Java 17",
                MemoryScope.PROJECT,
                "/p");
        LongTermMemory.SaveResult second = saveHuman(
                memory,
                "用户偏好 Java 17",
                MemoryScope.PROJECT,
                "/p");

        assertEquals(LongTermMemory.SaveResult.REJECT, second);
        assertEquals(1, memory.list("/p").size());
    }

    @Test
    void lowConfidenceAgentWriteShouldGoToReviewNotStore() {
        LongTermMemory memory = LongTermMemory.inMemory();
        MemoryRecord candidate =
                MemoryRecord.create("也许有缓存?", MemoryScope.PROJECT, "/p", MemorySource.AGENT, 0.4);
        LongTermMemory.SaveResult result = memory.save(candidate);

        assertEquals(LongTermMemory.SaveResult.DEFER, result);
        assertEquals(0, memory.list("/p").size());
        assertEquals(1, memory.pendingReview().size());
    }

    @Test
    void globalKnowledgeVisibleAcrossProjects() {
        LongTermMemory memory = LongTermMemory.inMemory();
        saveHuman(
                memory,
                "改完代码必须先跑测试",
                MemoryScope.GLOBAL,
                "");
        assertFalse(memory.retrieve("测试", "/whatever").isEmpty());
    }
}
