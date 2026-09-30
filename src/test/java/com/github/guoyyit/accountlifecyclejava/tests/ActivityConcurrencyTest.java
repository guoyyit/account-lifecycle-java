package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发上报与休眠扫描交错：账号锁串行化同一账号的判定，
 * 只要活动时间在阈值之内，谁后拿到锁都不能把活跃账号留在 DORMANT。
 */
class ActivityConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void interleavedScanAndReportNeverMisjudgesActiveAccount() throws Exception {
        TestHarness harness = new TestHarness(tempDir);
        int accountCount = 200;
        for (int i = 0; i < accountCount; i++) {
            harness.register("acct-" + i);
        }

        // 时钟拨到第 90 天临界之后 1 秒：若无新活动，扫描会把全部账号判休眠。
        Instant scanAt = TestHarness.T0.plus(Duration.ofDays(90)).plusSeconds(1);
        harness.setTime(scanAt);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            await(start);
            harness.scanner.scanOnce(scanAt);
            done.countDown();
        });
        pool.submit(() -> {
            await(start);
            // 同一时刻批量上报“刚刚活跃”，与扫描完全交错。
            for (int i = 0; i < accountCount; i++) {
                harness.activityService.report("acct-" + i, scanAt, "heartbeat-" + i);
            }
            done.countDown();
        });

        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 交错结束后再跑一次确定性扫描收敛：所有账号都在 scanAt 活跃，必须保持 ACTIVE。
        harness.scanner.scanOnce(scanAt);
        for (int i = 0; i < accountCount; i++) {
            Account account = harness.accountService.get("acct-" + i);
            assertEquals(AccountStatus.ACTIVE, account.getStatus(),
                    "账号被误判休眠: acct-" + i);
            assertEquals(scanAt, account.getLastActivityAt());
        }
    }

    @Test
    void concurrentReportsAreAllRecordedInOrder() throws Exception {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("acct-single");

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int second = t + 1;
            pool.submit(() -> {
                await(barrier);
                Instant at = TestHarness.T0.plusSeconds(second);
                harness.activityService.report("acct-single", at, "k-" + second);
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        Account account = harness.accountService.get("acct-single");
        assertEquals(TestHarness.T0.plusSeconds(threads), account.getLastActivityAt());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
