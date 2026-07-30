package com.xu.memory;

import com.xu.llm.LlmClient.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionStore 测试
 *
 * 用 new SessionStore(tempDir) 注入临时目录，
 * 避免污染真实 ~/.xcode/sessions。
 */
class SessionStoreTest {

    private SessionStore store;

    @TempDir
    Path sessionsDir;

    @BeforeEach
    void setUp() {
        store = new SessionStore(sessionsDir);
    }

    @Test
    void shouldReturnEmptyListForNewProject() throws Exception {
        List<Message> history = store.load("D:/projects/new-project");
        assertTrue(history.isEmpty());
    }

    @Test
    void shouldLoadWhatWasSaved() throws Exception {
        String project = "D:/projects/test-project";

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "你好"));
        messages.add(new Message("assistant", "你好！有什么可以帮你的？"));

        store.save(project, messages);

        List<Message> loaded = store.load(project);
        assertEquals(3, loaded.size());
        assertEquals("system", loaded.get(0).role);
        assertEquals("你好", loaded.get(1).content);
        assertEquals("你好！有什么可以帮你的？", loaded.get(2).content);
    }

    @Test
    void shouldOverwriteOnSave() throws Exception {
        String project = "D:/projects/overwrite-test";

        List<Message> first = List.of(new Message("user", "第一轮"));
        store.save(project, first);

        List<Message> second = List.of(new Message("user", "第二轮"));
        store.save(project, second);

        List<Message> loaded = store.load(project);
        assertEquals(1, loaded.size());
        assertEquals("第二轮", loaded.get(0).content);
    }

    @Test
    void shouldTruncateWhenExceedingMax() throws Exception {
        String project = "D:/projects/overflow-test";

        List<Message> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(new Message("user", "消息 " + i));
        }

        store.save(project, many);

        List<Message> loaded = store.load(project);
        assertTrue(loaded.size() <= 200);
        // 保留最近的消息
        assertEquals("消息 249", loaded.get(loaded.size() - 1).content);
    }

    @Test
    void shouldDeleteSessionFile() throws Exception {
        String project = "D:/projects/delete-test";

        store.save(project, List.of(new Message("user", "test")));
        // 确认文件确实写入了
        assertTrue(store.fileSize(project) > 0);

        store.delete(project);

        List<Message> loaded = store.load(project);
        assertTrue(loaded.isEmpty());
    }
}
