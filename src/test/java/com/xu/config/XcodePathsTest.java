package com.xu.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class XcodePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPlaceLogsUnderProjectDataDirectory() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        XcodePaths paths = new XcodePaths(project);

        assertEquals(
                paths.projectDataDir().resolve("logs"),
                paths.logsDir());
    }

    @Test
    void shouldIsolateLogsForProjectsWithSameName() throws Exception {
        Path first = Files.createDirectories(
                tempDir.resolve("workspace-a").resolve("demo"));
        Path second = Files.createDirectories(
                tempDir.resolve("workspace-b").resolve("demo"));

        XcodePaths firstPaths = new XcodePaths(first);
        XcodePaths secondPaths = new XcodePaths(second);

        assertNotEquals(firstPaths.logsDir(), secondPaths.logsDir());
    }
}
