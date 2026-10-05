package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuthExecutorsTest {
    @Test
    void overloadRejectsWithoutRunningDatabaseWorkOnCallerThread() throws Exception {
        var executor = AuthExecutors.database(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            executor.execute(() -> {
                worker.set(Thread.currentThread());
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });
            assertEquals(1, executor.getQueue().size());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(
                () -> fail("Rejected work must never execute")));
            assertNotSame(Thread.currentThread(), worker.get());
            assertEquals(1, executor.getQueue().size());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownDiscardsWaitingRequests() throws Exception {
        var executor = AuthExecutors.database(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.execute(() -> fail("Queued write must not run during shutdown"));
            assertEquals(1, executor.shutdownNow().size());
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            executor.shutdownNow();
        }
    }
}
