package com.xu.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTokenTest {

    @Test
    void aNewRunMustNotReactivateWorkersFromAnOldGeneration()
            throws Exception {
        CancellationToken token = new CancellationToken();
        token.beginRun();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch inherited = new CountDownLatch(1);
        CountDownLatch inspect = new CountDownLatch(1);
        try {
            Future<Boolean> oldRun = worker.submit(() -> {
                inherited.countDown();
                inspect.await();
                return token.isCancelled();
            });
            assertTrue(inherited.await(2, TimeUnit.SECONDS));

            token.cancel();
            token.beginRun();
            inspect.countDown();

            assertTrue(oldRun.get(2, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }
}
