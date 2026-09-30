package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.RecoverySession;
import com.github.guoyyit.accountlifecyclejava.domain.RecoverySessionStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.error.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.error.VerificationFailedException;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void dormantAccountRecycledOnlyAfterConfiguredBuffer() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("r-buffer");

        Instant dormantAt = TestHarness.T0.plus(Duration.ofDays(90)).plusSeconds(1);
        harness.scanner.scanOnce(dormantAt);
        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("r-buffer").getStatus());

        // 进入休眠后恰好 30 天（缓冲期临界）仍不回收。
        Instant bufferDeadline = dormantAt.plus(Duration.ofDays(30));
        harness.scanner.scanOnce(bufferDeadline);
        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("r-buffer").getStatus());

        // 超过 1 秒才回收。
        harness.scanner.scanOnce(bufferDeadline.plusSeconds(1));
        Account recycled = harness.accountService.get("r-buffer");
        assertEquals(AccountStatus.RECYCLED, recycled.getStatus());
        assertEquals(TransitionReason.DORMANCY_RECYCLED,
                recycled.getStatusReason());
    }

    @Test
    void recoveryRequiresVerificationAndSucceedsWithCode() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("r-ok");
        recycleViaDormancy(harness, "r-ok");

        RecoverySession session =
                harness.recoveryService.requestRecovery("r-ok", "req-1");
        assertEquals(RecoverySessionStatus.PENDING, session.getStatus());

        assertThrows(VerificationFailedException.class, () ->
                harness.recoveryService.completeRecovery(
                        session.getSessionId(), "000000"));

        // 验证失败不得留下半成品：账号仍 RECYCLED，会话标记 FAILED。
        assertEquals(AccountStatus.RECYCLED,
                harness.accountService.get("r-ok").getStatus());
        assertEquals(RecoverySessionStatus.FAILED,
                harness.recoveryService.findSession(session.getSessionId())
                        .orElseThrow().getStatus());

        // 失败后需重新发起恢复，拿到新会话再完成。
        RecoverySession retry =
                harness.recoveryService.requestRecovery("r-ok", "req-2");
        assertNotEquals(session.getSessionId(), retry.getSessionId());
        RecoverySession completed = harness.recoveryService
                .completeRecovery(retry.getSessionId(), retry.getVerificationCode());
        assertEquals(RecoverySessionStatus.SUCCEEDED, completed.getStatus());

        Account restored = harness.accountService.get("r-ok");
        assertEquals(AccountStatus.ACTIVE, restored.getStatus());
        assertEquals(TransitionReason.RECOVERED, restored.getStatusReason());
        assertEquals(restored.getStatusEnteredAt().plus(Duration.ofDays(365)),
                restored.getCredentialExpiresAt());
    }

    @Test
    void repeatedRecoveryRequestsWithSameKeyAreIdempotent() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("r-idem");
        recycleViaDormancy(harness, "r-idem");

        RecoverySession first =
                harness.recoveryService.requestRecovery("r-idem", "same-req");
        RecoverySession second =
                harness.recoveryService.requestRecovery("r-idem", "same-req");
        assertEquals(first.getSessionId(), second.getSessionId());
        assertEquals(first.getVerificationCode(), second.getVerificationCode());

        harness.recoveryService.completeRecovery(
                first.getSessionId(), first.getVerificationCode());
        long versionAfterRecovery =
                harness.accountService.get("r-idem").getVersion();
        // 对已完成会话重复提交验证，幂等返回成功，不重复应用迁移。
        RecoverySession again = harness.recoveryService.completeRecovery(
                first.getSessionId(), first.getVerificationCode());
        assertEquals(RecoverySessionStatus.SUCCEEDED, again.getStatus());
        assertEquals(versionAfterRecovery,
                harness.accountService.get("r-idem").getVersion());
    }

    @Test
    void recoveryOnlyAllowedFromRecycledState() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("r-active");
        assertThrows(LifecycleException.class, () ->
                harness.recoveryService.requestRecovery("r-active", "req-x"));
    }

    private void recycleViaDormancy(TestHarness harness, String id) {
        Instant dormantAt = TestHarness.T0.plus(Duration.ofDays(90)).plusSeconds(1);
        harness.scanner.scanOnce(dormantAt);
        harness.scanner.scanOnce(dormantAt.plus(Duration.ofDays(30)).plusSeconds(1));
        assertEquals(AccountStatus.RECYCLED,
                harness.accountService.get(id).getStatus());
    }

}
