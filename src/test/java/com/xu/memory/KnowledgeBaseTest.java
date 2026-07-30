package com.xu.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 子系统门面:写路径(治理决策落地)+ 读路径 端到端。 */
class KnowledgeBaseTest {

    @Test
    void humanSaveShouldCommitAndBeRetrievable() {
        KnowledgeBase kb = KnowledgeBase.inMemory();
        GovernanceGate.Decision d = kb.saveHuman("升级 okhttp 到 5.0", MemoryScope.PROJECT, "/p");

        assertEquals(GovernanceGate.Decision.COMMIT, d);
        assertEquals(1, kb.list("/p").size());
        assertFalse(kb.retrieve("帮我升级 okhttp", "/p").isEmpty());
    }

    @Test
    void duplicateSaveShouldNotGrowStore() {
        KnowledgeBase kb = KnowledgeBase.inMemory();
        kb.saveHuman("用户偏好 Java 17", MemoryScope.PROJECT, "/p");
        GovernanceGate.Decision d2 = kb.saveHuman("用户偏好 Java 17", MemoryScope.PROJECT, "/p");

        assertEquals(GovernanceGate.Decision.REJECT, d2);
        assertEquals(1, kb.list("/p").size());
    }

    @Test
    void lowConfidenceAgentWriteShouldGoToReviewNotStore() {
        KnowledgeBase kb = KnowledgeBase.inMemory();
        MemoryRecord candidate =
                MemoryRecord.create("也许有缓存?", MemoryScope.PROJECT, "/p", MemorySource.AGENT, 0.4);
        GovernanceGate.Decision d = kb.save(candidate);

        assertEquals(GovernanceGate.Decision.DEFER, d);
        assertEquals(0, kb.list("/p").size());        // 没入库
        assertEquals(1, kb.pendingReview().size());   // 挂在待确认队列
    }

    @Test
    void globalKnowledgeVisibleAcrossProjects() {
        KnowledgeBase kb = KnowledgeBase.inMemory();
        kb.saveHuman("改完代码必须先跑测试", MemoryScope.GLOBAL, "");
        // 在任意仓库都能捞到
        assertFalse(kb.retrieve("测试", "/whatever").isEmpty());
    }
}
