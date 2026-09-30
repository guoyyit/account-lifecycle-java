package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.RecoverySession;
import com.github.guoyyit.accountlifecyclejava.domain.RecoverySessionStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 进程崩溃/重启安全：全部内存状态由事件日志重放重建。
 * 已提交的迁移不丢、不重复应用；未提交的迁移整体消失且可安全重试。
 */
class RestartRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void stateIsRebuiltFromEventLogAfterRestart() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("x1");
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(122)));

        harness.restart();

        Account reloaded = harness.accountService.get("x1");
        assertEquals(AccountStatus.RECYCLED, reloaded.getStatus());
        assertEquals(TransitionReason.DORMANCY_RECYCLED,
                reloaded.getStatusReason());
        assertEquals(3, reloaded.getVersion(),
                "版本号由注册+休眠+回收事件确定性重放得到");
    }

    @Test
    void pendingRecoverySurvivesRestartAndCanComplete() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("x2");
        Instant recycledAt = TestHarness.T0.plus(Duration.ofDays(122));
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        harness.scanner.scanOnce(recycledAt);

        RecoverySession session =
                harness.recoveryService.requestRecovery("x2", "restart-req");

        harness.restart();

        // 会话由事件重建：仍是 PENDING，验证码一致，可继续完成恢复。
        RecoverySession restored = harness.recoveryService
                .findSession(session.getSessionId()).orElseThrow();
        assertEquals(RecoverySessionStatus.PENDING, restored.getStatus());
        assertEquals(session.getVerificationCode(),
                restored.getVerificationCode());

        RecoverySession completed = harness.recoveryService.completeRecovery(
                session.getSessionId(), session.getVerificationCode());
        assertEquals(RecoverySessionStatus.SUCCEEDED, completed.getStatus());
        assertEquals(AccountStatus.ACTIVE,
                harness.accountService.get("x2").getStatus());
    }

    @Test
    void idempotencyKeysSurviveRestart() {
        TestHarness harness = new TestHarness(tempDir);
        harness.accountService.register("x3", "default", "register-once");
        long version = harness.accountService.get("x3").getVersion();

        harness.restart();

        Account again =
                harness.accountService.register("x3", "default", "register-once");
        assertEquals(version, again.getVersion(),
                "重启后重复注册不得产生新事件或新版本");
    }

    @Test
    void scansAfterRestartApplyNoDuplicateTransitions() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("x4");
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        long versionAfterDormant =
                harness.accountService.get("x4").getVersion();

        harness.restart();

        // 并发/重启后多跑几次同一时刻的扫描：幂等，版本不增长。
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        assertEquals(versionAfterDormant,
                harness.accountService.get("x4").getVersion());
        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("x4").getStatus());
    }
}
