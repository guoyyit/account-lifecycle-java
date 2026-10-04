package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.GracePeriodExpiredException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentGraceRenewTest {

    @TempDir
    Path tempDir;

    private LifecycleHarness enterGrace(MutableClock clock, String accountId) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofHours(1)));
        harness.accountService.register(accountId, "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());
        return harness;
    }

    @RepeatedTest(30)
    void concurrentRenewalsAtDeadlineAreAllRejectedConsistently() throws Exception {
        MutableClock clock = MutableClock.startAt(10_000L);
        LifecycleHarness harness = enterGrace(clock, "cg1");
        clock.advanceMillis(50);

        runConcurrentRenewals(harness, "cg1", 8);

        assertEquals(AccountState.GRACE, harness.store.getAccount("cg1").state());
        harness.scanCoordinator.scanAccount("cg1");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("cg1").state());
    }

    @RepeatedTest(30)
    void concurrentRenewalsBeforeDeadlineAreAllAcceptedConsistently() throws Exception {
        MutableClock clock = MutableClock.startAt(20_000L);
        LifecycleHarness harness = enterGrace(clock, "cg2");
        clock.advanceMillis(49);

        List<Boolean> results = runConcurrentRenewals(harness, "cg2", 8);
        assertTrue(results.stream().allMatch(accepted -> accepted),
                "截止前并发更新必须全部被接受: " + results);

        assertEquals(AccountState.ACTIVE, harness.store.getAccount("cg2").state());
        harness.scanCoordinator.scanAccount("cg2");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("cg2").state());
    }

    @RepeatedTest(30)
    void interleavedScanAndRenewAtSameInstantHaveOneOutcome() throws Exception {
        MutableClock clock = MutableClock.startAt(30_000L);
        LifecycleHarness harness = enterGrace(clock, "cg3");
        clock.advanceMillis(50);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Boolean> renew = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                harness.credentialService.renew("cg3", Duration.ofMillis(1_000));
                return true;
            } catch (LifecycleException ex) {
                return false;
            }
        });
        Future<Boolean> scan = pool.submit(() -> {
            ready.countDown();
            start.await();
            return harness.scanCoordinator.scanAccount("cg3");
        });
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        boolean renewed = renew.get(5, TimeUnit.SECONDS);
        boolean scanned = scan.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertFalse(renewed, "截止时刻更新必须被拒绝");
        assertTrue(scanned, "截止时刻扫描必须回收");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("cg3").state());
    }

    private List<Boolean> runConcurrentRenewals(LifecycleHarness harness, String accountId,
                                                int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    harness.credentialService.renew(accountId, Duration.ofMillis(1_000));
                    return true;
                } catch (GracePeriodExpiredException ex) {
                    return false;
                }
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        List<Boolean> results = new java.util.ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        long accepted = results.stream().filter(Boolean::booleanValue).count();
        assertTrue(accepted == 0 || accepted == results.size(),
                "并发更新结果必须一致（全接受或全拒绝）: " + results);
        return results;
    }
}
