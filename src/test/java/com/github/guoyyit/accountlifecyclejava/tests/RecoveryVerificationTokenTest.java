package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.lifecycle.RecoveryVerificationException;
import com.github.guoyyit.accountlifecyclejava.engine.RecoveryService;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryChallenge;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryVerificationTokenTest {

    @TempDir
    Path tempDir;

    private LifecycleHarness recycled(String accountId, MutableClock clock) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofHours(1)));
        harness.accountService.register(accountId, "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());
        clock.advanceMillis(50);
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.RECYCLED, harness.store.getAccount(accountId).state());
        return harness;
    }

    @Test
    void missingAndWrongTokenFailWithDistinctReasons() {
        MutableClock clock = MutableClock.startAt(80_000L);
        LifecycleHarness harness = recycled("v1", clock);
        RecoveryChallenge challenge = harness.recoveryService.startRecovery("v1", "rv1");

        RecoveryVerificationException missing = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("rv1", null));
        assertEquals(RecoveryService.TOKEN_MISSING, missing.code());

        RecoveryVerificationException wrong = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("rv1", "forged-token"));
        assertEquals(RecoveryService.TOKEN_MISMATCH, wrong.code());

        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("rv1").status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("v1").state());

        PendingRecovery verified = harness.recoveryService.verify("rv1",
                challenge.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verified.status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("v1").state());

        List<AuditEntry> attempts = harness.auditQueryService.byAccount("v1").stream()
                .filter(e -> e.eventType().equals(EventType.RECOVERY_VERIFICATION_FAILED.name()))
                .toList();
        assertEquals(2, attempts.size());
        assertAuditReason(attempts, RecoveryService.TOKEN_MISSING);
        assertAuditReason(attempts, RecoveryService.TOKEN_MISMATCH);
    }

    @Test
    void expiredTokenCannotActivateAccount() {
        MutableClock clock = MutableClock.startAt(81_000L);
        LifecycleHarness harness = recycled("v2", clock);
        RecoveryChallenge challenge = harness.recoveryService.startRecovery("v2", "rv2");

        clock.advanceMillis(10 * 60 * 1_000L);
        RecoveryVerificationException expired = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("rv2", challenge.verificationToken()));
        assertEquals(RecoveryService.TOKEN_EXPIRED, expired.code());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("v2").state());
        assertEquals(RecoveryStatus.FAILED, harness.store.getRecovery("rv2").status());

        assertThrows(com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException.class,
                () -> harness.recoveryService.verify("rv2", challenge.verificationToken()));
    }

    @Test
    void tokenIssuedForOneRequestCannotVerifyAnotherRequest() {
        MutableClock clock = MutableClock.startAt(82_000L);
        LifecycleHarness harness = recycled("v3", clock);
        harness.accountService.register("v4", "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("v4");
        assertEquals(AccountState.GRACE, harness.store.getAccount("v4").state());
        clock.advanceMillis(50);
        harness.scanCoordinator.scanAccount("v4");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("v4").state());

        RecoveryChallenge first = harness.recoveryService.startRecovery("v3", "rv3");
        RecoveryChallenge second = harness.recoveryService.startRecovery("v4", "rv4");

        RecoveryVerificationException reused = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("rv4", first.verificationToken()));
        assertEquals(RecoveryService.TOKEN_FOREIGN, reused.code());
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("rv4").status());

        RecoveryVerificationException otherWay = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("rv3", second.verificationToken()));
        assertEquals(RecoveryService.TOKEN_FOREIGN, otherWay.code());

        PendingRecovery verified = harness.recoveryService.verify("rv3",
                first.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verified.status());
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("rv4").status());
    }

    @Test
    void successfulVerificationIsIdempotentAndTokenCannotBeReusedAfter() {
        MutableClock clock = MutableClock.startAt(83_000L);
        LifecycleHarness harness = recycled("v5", clock);
        RecoveryChallenge challenge = harness.recoveryService.startRecovery("v5", "rv5");

        harness.recoveryService.verify("rv5", challenge.verificationToken());
        long sequenceAfterVerify = harness.log.lastSequence();
        PendingRecovery again = harness.recoveryService.verify("rv5",
                challenge.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, again.status());
        assertEquals(sequenceAfterVerify, harness.log.lastSequence(),
                "重复验证保持幂等，不追加审计事件");

        harness.accountService.register("v6", "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("v6");
        assertEquals(AccountState.GRACE, harness.store.getAccount("v6").state());
        clock.advanceMillis(50);
        harness.scanCoordinator.scanAccount("v6");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("v6").state());
        harness.recoveryService.startRecovery("v6", "rv6");
        RecoveryVerificationException reuse = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("rv6", challenge.verificationToken()));
        assertEquals(RecoveryService.TOKEN_FOREIGN, reuse.code());

        harness.recoveryService.complete("rv5");
        PendingRecovery completedAgain = harness.recoveryService.complete("rv5");
        assertEquals(RecoveryStatus.COMPLETED, completedAgain.status());
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("v5").state());
    }

    private static void assertAuditReason(List<AuditEntry> attempts, String reason) {
        boolean found = attempts.stream().anyMatch(e -> e.detail().contains(reason));
        org.junit.jupiter.api.Assertions.assertTrue(found,
                "审计记录中应能区分失败原因: " + reason + "，实际: "
                        + attempts.stream().map(AuditEntry::detail).toList());
    }
}
