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
 * 扫描任务并发执行：同一账号的迁移串行且幂等，版本号只能各步 +1。
 */
class ConcurrentScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentScansApplyEachTransitionAtMostOnce() throws Exception {
        TestHarness harness = new TestHarness(tempDir);
        int accountCount = 100;
        for (int i = 0; i < accountCount; i++) {
            harness.register("c-" + i);
        }
        Instant day91 = TestHarness.T0.plus(Duration.ofDays(91));

        int scanners = 4;
        ExecutorService pool = Executors.newFixedThreadPool(scanners);
        CyclicBarrier barrier = new CyclicBarrier(scanners);
        CountDownLatch done = new CountDownLatch(scanners);
        for (int s = 0; s < scanners; s++) {
            pool.submit(() -> {
                try {
                    try {
                        barrier.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    // 4 个扫描器在同一时刻并发重复扫描，休眠迁移只能被应用一次。
                    harness.scanner.scanOnce(day91);
                    harness.scanner.scanOnce(day91);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 缓冲期过后再扫描：每账号恰好再迁移一次到 RECYCLED。
        harness.scanner.scanOnce(day91.plus(Duration.ofDays(31)));

        for (int i = 0; i < accountCount; i++) {
            Account account = harness.accountService.get("c-" + i);
            assertEquals(AccountStatus.RECYCLED, account.getStatus());
            // 注册 v1 -> 休眠 v2 -> 回收 v3：并发下也必须精确为 3，绝不重复应用。
            assertEquals(3, account.getVersion(), "账号 c-" + i);
        }
    }
}
