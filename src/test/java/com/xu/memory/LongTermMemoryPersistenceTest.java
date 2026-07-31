package com.xu.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 长期记忆的作用域、容量和 JSON 落盘恢复测试。 */
class LongTermMemoryPersistenceTest {

    @TempDir
    Path tempDir;

    private MemoryRecord rec(String content, MemoryScope scope, String pk) {
        return MemoryRecord.create(content, scope, pk, MemorySource.HUMAN, 0.9);
    }

    @Test
    void shouldPutAndList() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(rec("用户偏好 Java 17", MemoryScope.PROJECT, "/p"));
        memory.save(rec("okhttp 用 5.0", MemoryScope.PROJECT, "/p"));
        assertEquals(2, memory.list("/p").size());
    }

    @Test
    void visibleShouldRespectScope() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(rec("通用偏好", MemoryScope.GLOBAL, ""));
        memory.save(rec("项目A专用", MemoryScope.PROJECT, "/a"));

        assertEquals(2, memory.list("/a").size());
        List<MemoryRecord> inB = memory.list("/b");
        assertEquals(1, inB.size());
        assertEquals("通用偏好", inB.get(0).content());
    }

    @Test
    void shouldClear() {
        LongTermMemory memory = LongTermMemory.inMemory();
        memory.save(rec("可清空", MemoryScope.PROJECT, "/p"));
        memory.clear();
        assertTrue(memory.list("/p").isEmpty());
    }

    @Test
    void shouldEvictOldestWhenOverCapacity() {
        LongTermMemory memory = LongTermMemory.inMemory();
        for (int i = 0; i < 55; i++) {
            memory.save(rec(
                    UUID.randomUUID().toString(),
                    MemoryScope.PROJECT,
                    "/p"));
        }
        assertTrue(memory.list("/p").size() <= 50);
    }

    @Test
    void shouldPersistAndReload() {
        LongTermMemory memory = LongTermMemory.create(tempDir);
        memory.save(rec("落盘的知识", MemoryScope.GLOBAL, ""));

        LongTermMemory reloaded = LongTermMemory.create(tempDir);
        assertEquals(1, reloaded.list("/anything").size());
        assertEquals(
                "落盘的知识",
                reloaded.list("/anything").get(0).content());
    }
}
