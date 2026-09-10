package com.xu.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvalProcessTest {
    @TempDir Path root;
    @Test void deadlineTerminatesAStillRunningJvm() throws Exception {
        var exit = EvalProcess.run(List.of(EvalProcess.jdk("java"), "-cp", EvalProcess.classpath(), Sleeper.class.getName()),
                root, root.resolve("process.log"), Duration.ofMillis(300));
        assertTrue(exit.timedOut());assertEquals(-1,exit.code());
    }
    public static class Sleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(30_000); }
    }
}
