package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentActivityScanTest {

    @TempDir
    Path tempDir;

    @Test
    void interleavedScanAndReportNeverMisfiresActiveAccount() throws Exception {
        MutableClock clock = MutableClock.startAt(5_000_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(200), Duration.ofDays(1),
                        Duration.ofDays(10), Duration.ofHours(1)));
        harness.accountService.register("busy", "default", null, null);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean failure = new AtomicBoolean(false);

        Runnable reporter = () -> {
            awaitQuietly(start);
            while (!stop.get()) {
                try {
                    harness.activityService.report("busy");
                    clock.advanceMillis(10);
                } catch (Exception e) {
                    failure.set(true);
                }
            }
        };
        Runnable scanner = () -> {
            awaitQuietly(start);
            while (!stop.get()) {
                harness.scanCoordinator.scanAccount("busy");
                if (harness.store.getAccount("busy").state() == AccountState.DORMANT) {
                    failure.set(true);
                }
            }
        };

        for (int i = 0; i < 2; i++) {
            pool.submit(reporter);
        }
        for (int i = 0; i < 2; i++) {
            pool.submit(scanner);
        }
        start.countDown();
        Thread.sleep(1_500);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(!failure.get(), "持续上报活动的账号不应被扫描误判为休眠");
        assertTrue(harness.store.getAccount("busy").state() == AccountState.ACTIVE);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
