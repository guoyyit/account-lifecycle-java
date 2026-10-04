package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.engine.AccountService;
import com.github.guoyyit.accountlifecyclejava.lifecycle.GracePeriodExpiredException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentRenewalConsistencyTest {

    private static final long T0 = 700_000L;
    private static final Duration CREDENTIAL_TTL = Duration.ofMillis(10);
    private static final Duration GRACE = Duration.ofMillis(50);

    @TempDir
    Path tempDir;

    private static final class SignalingClock extends MutableClock {
        private volatile CountDownLatch armed;

        SignalingClock(long startMillis) {
            super(startMillis);
        }

        void armNextCall(CountDownLatch latch) {
            this.armed = latch;
        }

        @Override
        public long nowMillis() {
            CountDownLatch latch = armed;
            if (latch != null) {
                armed = null;
                latch.countDown();
            }
            return super.nowMillis();
        }
    }

    private LifecycleHarness harnessWith(MutableClock clock) {
        return new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), GRACE,
                        Duration.ofDays(10), Duration.ofHours(1)));
    }

    private long toGrace(LifecycleHarness harness, MutableClock clock, String accountId) {
        harness.accountService.register(accountId, "default", CREDENTIAL_TTL, null);
        clock.advanceMillis(CREDENTIAL_TTL.toMillis());
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());
        return harness.store.getAccount(accountId).stateEnteredAtMillis() + GRACE.toMillis();
    }

    @Test
    void submissionBeforeDeadlineWinsEvenIfProcessedAfterDeadline() throws Exception {
        SignalingClock clock = new SignalingClock(T0);
        LifecycleHarness harness = harnessWith(clock);
        long deadline = toGrace(harness, clock, "c1");

        CountDownLatch captured = new CountDownLatch(1);
        clock.armNextCall(captured);
        clock.setMillis(deadline - 1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Object lock = AccountService.lockFor("c1");
            Future<?> renewal;
            synchronized (lock) {
                renewal = pool.submit(
                        () -> harness.credentialService.renew("c1", Duration.ofDays(1)));
                assertTrue(captured.await(5, TimeUnit.SECONDS),
                        "更新请求应在等待锁之前已记录提交时刻");
                clock.setMillis(deadline + 60_000);
            }
            renewal.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        var account = harness.store.getAccount("c1");
        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED, account.stateReason());
    }

    @Test
    void concurrentRenewalsAtDeadlineAreAllRejected() throws Exception {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = harnessWith(clock);

        for (int round = 0; round < 5; round++) {
            String accountId = "d" + round;
            long deadline = toGrace(harness, clock, accountId);
            clock.setMillis(deadline);

            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitQuietly(start);
                    try {
                        harness.credentialService.renew(accountId, Duration.ofDays(1));
                    } catch (GracePeriodExpiredException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            pool.shutdownNow();

            assertEquals(threads, rejected.get(), "截止时刻提交的全部更新都必须被拒绝");
            assertEquals(0, unexpected.get());
            assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());

            harness.scanCoordinator.scanAccount(accountId);
            assertEquals(AccountState.RECYCLED, harness.store.getAccount(accountId).state());
        }
    }

    @Test
    void concurrentRenewalsBeforeDeadlineAreAllAccepted() throws Exception {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = harnessWith(clock);
        long deadline = toGrace(harness, clock, "e1");
        clock.setMillis(deadline - 1);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                try {
                    harness.credentialService.renew("e1", Duration.ofDays(1));
                    accepted.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(threads, accepted.get(), "截止前提交的全部更新都必须被接受");
        assertEquals(0, unexpected.get());
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("e1").state());

        List<AuditEntry> entries = harness.auditQueryService.query(new AuditQuery("e1", null, null));
        long renewed = entries.stream().filter(e -> "CREDENTIAL_RENEWED".equals(e.eventType())).count();
        long updated = entries.stream().filter(e -> "CREDENTIAL_UPDATED".equals(e.eventType())).count();
        assertEquals(1, renewed, "恰好一次宽限期恢复");
        assertEquals(threads - 1, updated, "其余为正常凭据更新");
        assertNull(entries.stream().filter(e -> "CREDENTIAL_RENEWAL_REJECTED".equals(e.eventType()))
                .findAny().orElse(null));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
