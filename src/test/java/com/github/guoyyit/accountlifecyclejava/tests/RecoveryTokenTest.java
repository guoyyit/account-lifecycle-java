package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.VerificationFailedException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import com.github.guoyyit.accountlifecyclejava.model.VerifyRejectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTokenTest {

    private static final long T0 = 1_300_000L;
    private static final Duration RECOVERY_TTL = Duration.ofMillis(100);

    @TempDir
    Path tempDir;

    private LifecycleHarness newHarness(MutableClock clock) {
        return new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(10), Duration.ofDays(1),
                        Duration.ofMillis(10), RECOVERY_TTL));
    }

    private LifecycleHarness recycledAccount(MutableClock clock, String accountId) {
        LifecycleHarness harness = newHarness(clock);
        harness.accountService.register(accountId, "default", null, null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount(accountId);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.RECYCLED, harness.store.getAccount(accountId).state());
        return harness;
    }

    private static void assertVerifyFails(LifecycleHarness harness, String requestId,
                                          String token, VerifyRejectReason reason) {
        VerificationFailedException ex = assertThrows(VerificationFailedException.class,
                () -> harness.recoveryService.verify(requestId, token));
        assertEquals(reason, ex.reason());
    }

    private static List<AuditEntry> auditOf(LifecycleHarness harness, String accountId) {
        return harness.auditQueryService.query(new AuditQuery(accountId, null, null));
    }

    @Test
    void missingTokenFailsAndKeepsRecycledState() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t1");
        harness.recoveryService.startRecovery("t1", "req-t1");
        assertVerifyFails(harness, "req-t1", null, VerifyRejectReason.TOKEN_MISSING);
        assertVerifyFails(harness, "req-t1", "  ", VerifyRejectReason.TOKEN_MISSING);
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("req-t1").status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("t1").state());
        long rejections = auditOf(harness, "t1").stream()
                .filter(e -> "RECOVERY_VERIFY_REJECTED".equals(e.eventType()))
                .filter(e -> e.detail().contains("TOKEN_MISSING"))
                .count();
        assertEquals(2, rejections);
    }

    @Test
    void wrongTokenFailsThenCorrectTokenSucceeds() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t2");
        PendingRecovery started = harness.recoveryService.startRecovery("t2", "req-t2");
        assertVerifyFails(harness, "req-t2", "wrong-token", VerifyRejectReason.TOKEN_MISMATCH);
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("req-t2").status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("t2").state());
        PendingRecovery verified = harness.recoveryService.verify("req-t2",
                started.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verified.status());
    }

    @Test
    void tokenIssuedForOneRequestIsRejectedByAnother() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t3a");
        harness.accountService.register("t3b", "default", null, null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("t3b");
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("t3b");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("t3b").state());
        PendingRecovery first = harness.recoveryService.startRecovery("t3a", "req-t3a");
        PendingRecovery second = harness.recoveryService.startRecovery("t3b", "req-t3b");
        assertNotEquals(first.verificationToken(), second.verificationToken());
        assertVerifyFails(harness, "req-t3b", first.verificationToken(),
                VerifyRejectReason.TOKEN_MISMATCH);
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("req-t3b").status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("t3b").state());
        PendingRecovery verified = harness.recoveryService.verify("req-t3b",
                second.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verified.status());
    }

    @Test
    void expiredTokenFailsRequestTerminally() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t4");
        PendingRecovery started = harness.recoveryService.startRecovery("t4", "req-t4");
        clock.advanceMillis(RECOVERY_TTL.toMillis());
        assertVerifyFails(harness, "req-t4", started.verificationToken(),
                VerifyRejectReason.TOKEN_EXPIRED);
        PendingRecovery failed = harness.store.getRecovery("req-t4");
        assertEquals(RecoveryStatus.FAILED, failed.status());
        assertEquals("验证凭据已过期", failed.failReason());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("t4").state());
        assertThrows(InvalidTransitionException.class,
                () -> harness.recoveryService.complete("req-t4"));
        boolean failedEventAudited = auditOf(harness, "t4").stream()
                .anyMatch(e -> "RECOVERY_FAILED".equals(e.eventType())
                        && e.detail().contains("验证凭据已过期"));
        assertTrue(failedEventAudited);
    }

    @Test
    void reverifyAfterSuccessIsIdempotentOnlyWithIssuedToken() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t5");
        PendingRecovery started = harness.recoveryService.startRecovery("t5", "req-t5");
        harness.recoveryService.verify("req-t5", started.verificationToken());
        long auditSize = auditOf(harness, "t5").size();
        PendingRecovery again = harness.recoveryService.verify("req-t5",
                started.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, again.status());
        assertEquals(auditSize, auditOf(harness, "t5").size(), "幂等重复验证不产生新审计");
        harness.recoveryService.complete("req-t5");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("t5").state());
        PendingRecovery afterComplete = harness.recoveryService.verify("req-t5",
                started.verificationToken());
        assertEquals(RecoveryStatus.COMPLETED, afterComplete.status());
        assertVerifyFails(harness, "req-t5", "wrong-token", VerifyRejectReason.TOKEN_MISMATCH);
        assertEquals(RecoveryStatus.COMPLETED, harness.store.getRecovery("req-t5").status());
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("t5").state());
    }

    @Test
    void failedRecoveryIsIdempotentAndNewRequestGetsFreshToken() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t6");
        PendingRecovery started = harness.recoveryService.startRecovery("t6", "req-t6");
        clock.advanceMillis(RECOVERY_TTL.toMillis());
        assertVerifyFails(harness, "req-t6", started.verificationToken(),
                VerifyRejectReason.TOKEN_EXPIRED);
        assertEquals(RecoveryStatus.FAILED, harness.store.getRecovery("req-t6").status());
        PendingRecovery same = harness.recoveryService.startRecovery("t6", "req-t6");
        assertEquals(RecoveryStatus.FAILED, same.status());
        assertThrows(InvalidTransitionException.class,
                () -> harness.recoveryService.complete("req-t6"));
        PendingRecovery retry = harness.recoveryService.startRecovery("t6", "req-t6-b");
        assertEquals(RecoveryStatus.PENDING, retry.status());
        assertNotEquals(started.verificationToken(), retry.verificationToken());
        assertVerifyFails(harness, "req-t6-b", started.verificationToken(),
                VerifyRejectReason.TOKEN_MISMATCH);
        harness.recoveryService.verify("req-t6-b", retry.verificationToken());
        harness.recoveryService.complete("req-t6-b");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("t6").state());
    }

    @Test
    void everyVerificationAttemptIsAuditedWithDistinctReason() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = recycledAccount(clock, "t7");
        PendingRecovery started = harness.recoveryService.startRecovery("t7", "req-t7");
        assertVerifyFails(harness, "req-t7", null, VerifyRejectReason.TOKEN_MISSING);
        assertVerifyFails(harness, "req-t7", "bad", VerifyRejectReason.TOKEN_MISMATCH);
        harness.recoveryService.verify("req-t7", started.verificationToken());
        List<AuditEntry> entries = auditOf(harness, "t7");
        assertTrue(entries.stream().anyMatch(e -> "RECOVERY_VERIFY_REJECTED".equals(e.eventType())
                && e.detail().contains("TOKEN_MISSING")));
        assertTrue(entries.stream().anyMatch(e -> "RECOVERY_VERIFY_REJECTED".equals(e.eventType())
                && e.detail().contains("TOKEN_MISMATCH")));
        assertTrue(entries.stream().anyMatch(e -> "RECOVERY_VERIFIED".equals(e.eventType())));
        LifecycleHarness reopened = harness.reopen();
        assertEquals(RecoveryStatus.VERIFIED, reopened.store.getRecovery("req-t7").status());
        assertEquals(entries.size(), auditOf(reopened, "t7").size());
    }
}
