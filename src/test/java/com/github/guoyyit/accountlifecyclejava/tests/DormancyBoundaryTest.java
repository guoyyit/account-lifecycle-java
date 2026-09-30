package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 休眠判定边界（锁定语义）：默认阈值 90 天。
 * lastActivity == at - 90d 的临界时刻仍算活跃；只有严格早于该时刻才判休眠。
 */
class DormancyBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void activeExactlyAtBoundaryStaysActive() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("a-boundary");

        Instant boundary = TestHarness.T0.plus(Duration.ofDays(90));
        harness.setTime(boundary);

        harness.scanner.scanOnce(boundary);

        Account reloaded = harness.accountService.get("a-boundary");
        assertEquals(AccountStatus.ACTIVE, reloaded.getStatus());
    }

    @Test
    void dormantOnlyWhenStrictlyBeyondBoundary() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("a-beyond");

        Instant boundary = TestHarness.T0.plus(Duration.ofDays(90));
        Instant justBeyond = boundary.plusSeconds(1);
        harness.setTime(justBeyond);
        harness.scanner.scanOnce(justBeyond);

        Account reloaded = harness.accountService.get("a-beyond");
        assertEquals(AccountStatus.DORMANT, reloaded.getStatus());
        assertEquals(TransitionReason.DORMANT_BY_INACTIVITY,
                reloaded.getStatusReason());
        assertEquals(justBeyond, reloaded.getStatusEnteredAt());
    }

    @Test
    void activityAfterThresholdKeepsAccountActive() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("a-active");

        Instant day60 = TestHarness.T0.plus(Duration.ofDays(60));
        harness.activityService.report("a-active", day60, "activity-60");

        Instant day150 = TestHarness.T0.plus(Duration.ofDays(150));
        harness.setTime(day150);
        harness.scanner.scanOnce(day150);
        assertEquals(AccountStatus.ACTIVE,
                harness.accountService.get("a-active").getStatus());

        Instant day151 = day60.plus(Duration.ofDays(90)).plusSeconds(1);
        harness.scanner.scanOnce(day151);
        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("a-active").getStatus());
    }

    @Test
    void dormantAccountWakesOnNewActivity() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("a-wake");

        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("a-wake").getStatus());

        Instant back = TestHarness.T0.plus(Duration.ofDays(92));
        Account woken = harness.activityService.report("a-wake", back, "wake-1");
        assertEquals(AccountStatus.ACTIVE, woken.getStatus());
        assertEquals(TransitionReason.REACTIVATED_BY_ACTIVITY,
                woken.getStatusReason());
    }
}
