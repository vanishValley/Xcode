package com.xu.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 纯存储层:存取 / scope 过滤 / 容量淘汰 / 落盘恢复。去重不在这层(见 GovernanceGateTest)。 */
class KnowledgeStoreTest {

    @TempDir
    Path tempDir;

    private MemoryRecord rec(String content, MemoryScope scope, String pk) {
        return MemoryRecord.create(content, scope, pk, MemorySource.HUMAN, 0.9);
    }

    @Test
    void shouldPutAndList() {
        KnowledgeStore store = new KnowledgeStore();
        store.put(rec("用户偏好 Java 17", MemoryScope.PROJECT, "/p"));
        store.put(rec("okhttp 用 5.0", MemoryScope.PROJECT, "/p"));
        assertEquals(2, store.listAll("/p").size());
    }

    @Test
    void visibleShouldRespectScope() {
        KnowledgeStore store = new KnowledgeStore();
        store.put(rec("通用偏好", MemoryScope.GLOBAL, ""));
        store.put(rec("项目A专用", MemoryScope.PROJECT, "/a"));

        // 在 A 仓库:看到 global + 本项目 = 2
        assertEquals(2, store.visible("/a").size());
        // 在 B 仓库:只看到 global = 1
        List<MemoryRecord> inB = store.visible("/b");
        assertEquals(1, inB.size());
        assertEquals("通用偏好", inB.get(0).content());
    }

    @Test
    void siblingsShouldMatchScopeAndProject() {
        KnowledgeStore store = new KnowledgeStore();
        MemoryRecord a1 = rec("A1", MemoryScope.PROJECT, "/a");
        store.put(a1);
        store.put(rec("B1", MemoryScope.PROJECT, "/b"));
        store.put(rec("G1", MemoryScope.GLOBAL, ""));
        // 与 /a 的 PROJECT 候选同组的,只有 /a 那条
        MemoryRecord candA = rec("A2", MemoryScope.PROJECT, "/a");
        assertEquals(1, store.siblingsOf(candA).size());
    }

    @Test
    void shouldDeleteAndClear() {
        KnowledgeStore store = new KnowledgeStore();
        MemoryRecord r = rec("可删", MemoryScope.PROJECT, "/p");
        store.put(r);
        assertTrue(store.delete(r.id()));
        assertEquals(0, store.size());

        store.put(rec("再存一条", MemoryScope.PROJECT, "/p"));
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void shouldEvictOldestWhenOverCapacity() {
        KnowledgeStore store = new KnowledgeStore();
        for (int i = 0; i < 55; i++) {
            store.put(rec("记忆 " + i, MemoryScope.PROJECT, "/p"));
        }
        assertTrue(store.size() <= 50);
    }

    @Test
    void shouldPersistAndReload() {
        KnowledgeStore store = new KnowledgeStore(tempDir);
        store.put(rec("落盘的知识", MemoryScope.GLOBAL, ""));

        // 新实例从同目录加载
        KnowledgeStore reloaded = new KnowledgeStore(tempDir);
        assertEquals(1, reloaded.size());
        assertEquals("落盘的知识", reloaded.visible("/anything").get(0).content());
    }
}
