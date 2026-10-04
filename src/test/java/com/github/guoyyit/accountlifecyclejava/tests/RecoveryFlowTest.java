package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.RecoveryVerificationException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryChallenge;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void dormantBufferRecycleThenVerifiedRecovery() {
        MutableClock clock = MutableClock.startAt(30_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofMillis(50), Duration.ofHours(1)));
        harness.accountService.register("r1", "default", null, null);

        clock.advanceMillis(100);
        harness.scanCoordinator.scanAccount("r1");
        assertEquals(AccountState.DORMANT, harness.store.getAccount("r1").state());

        clock.advanceMillis(49);
        harness.scanCoordinator.scanAccount("r1");
        assertEquals(AccountState.DORMANT, harness.store.getAccount("r1").state());

        clock.advanceMillis(1);
        harness.scanCoordinator.scanAccount("r1");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("r1").state());

        harness.accountService.register("r1-active", "default", null, null);
        assertThrows(InvalidTransitionException.class,
                () -> harness.recoveryService.startRecovery("r1-active", "x"));

        RecoveryChallenge challenge = harness.recoveryService.startRecovery("r1", "req-1");
        assertEquals(RecoveryStatus.PENDING, challenge.recovery().status());
        assertNotNull(challenge.verificationToken());

        assertThrows(InvalidTransitionException.class,
                () -> harness.recoveryService.complete("req-1"));

        RecoveryVerificationException missing = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("req-1", null));
        assertEquals("RECOVERY_TOKEN_MISSING", missing.code());

        RecoveryVerificationException blank = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("req-1", "  "));
        assertEquals("RECOVERY_TOKEN_MISSING", blank.code());

        RecoveryVerificationException wrong = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("req-1", "not-the-issued-token"));
        assertEquals("RECOVERY_TOKEN_MISMATCH", wrong.code());
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("req-1").status());

        PendingRecovery verified = harness.recoveryService.verify("req-1",
                challenge.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verified.status());

        PendingRecovery verifiedAgain = harness.recoveryService.verify("req-1",
                challenge.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verifiedAgain.status());

        PendingRecovery completed = harness.recoveryService.complete("req-1");
        assertEquals(RecoveryStatus.COMPLETED, completed.status());
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("r1").state());
        assertEquals(TransitionReason.RECOVERY_VERIFIED,
                harness.store.getAccount("r1").stateReason());

        PendingRecovery completedAgain = harness.recoveryService.complete("req-1");
        assertEquals(RecoveryStatus.COMPLETED, completedAgain.status());
    }

    @Test
    void wrongTokenLeavesRecycledStateAndPendingRequest() {
        MutableClock clock = MutableClock.startAt(40_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(10), Duration.ofDays(1),
                        Duration.ofMillis(10), Duration.ofMillis(100)));
        harness.accountService.register("r2", "default", null, null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("r2");
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("r2");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("r2").state());

        RecoveryChallenge challenge = harness.recoveryService.startRecovery("r2", "req-2");
        RecoveryVerificationException wrong = assertThrows(RecoveryVerificationException.class,
                () -> harness.recoveryService.verify("req-2", "not-the-issued-token"));
        assertEquals("RECOVERY_TOKEN_MISMATCH", wrong.code());
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("req-2").status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("r2").state());

        assertThrows(InvalidTransitionException.class,
                () -> harness.recoveryService.complete("req-2"));

        PendingRecovery verified = harness.recoveryService.verify("req-2",
                challenge.verificationToken());
        assertEquals(RecoveryStatus.VERIFIED, verified.status());

        RecoveryChallenge retry = harness.recoveryService.startRecovery("r2", "req-3");
        assertEquals("req-2", retry.recovery().requestId());
    }

    @Test
    void stalePendingRecoveryFailsAfterTtl() {
        MutableClock clock = MutableClock.startAt(50_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(10), Duration.ofDays(1),
                        Duration.ofMillis(10), Duration.ofMillis(100)));
        harness.accountService.register("r3", "default", null, null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("r3");
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("r3");

        RecoveryChallenge stale = harness.recoveryService.startRecovery("r3", "req-ttl");
        assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("req-ttl").status());
        clock.advanceMillis(100);
        harness.scanCoordinator.scanAccount("r3");
        assertEquals(RecoveryStatus.FAILED,
                harness.store.getRecovery("req-ttl").status());
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("r3").state());

        assertThrows(InvalidTransitionException.class,
                () -> harness.recoveryService.verify("req-ttl", stale.verificationToken()));
    }
}
