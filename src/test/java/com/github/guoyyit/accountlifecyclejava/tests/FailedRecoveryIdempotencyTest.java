package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.RecoveryVerificationException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryChallenge;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailedRecoveryIdempotencyTest {

    @TempDir
    Path tempDir;

    private LifecycleHarness recycled(MutableClock clock, String accountId) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofMillis(100)));
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
    void wrongAttemptsAreIdempotentAndEventuallySucceedWithIssuedToken() {
        MutableClock clock = MutableClock.startAt(90_000L);
        LifecycleHarness harness = recycled(clock, "f1");
        RecoveryChallenge challenge = harness.recoveryService.startRecovery("f1", "rf1");

        for (int i = 0; i < 3; i++) {
            assertThrows(RecoveryVerificationException.class,
                    () -> harness.recoveryService.verify("rf1", "wrong"));
            assertEquals(RecoveryStatus.PENDING, harness.store.getRecovery("rf1").status());
            assertEquals(AccountState.RECYCLED, harness.store.getAccount("f1").state());
        }

        harness.recoveryService.verify("rf1", challenge.verificationToken());
        harness.recoveryService.complete("rf1");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("f1").state());

        assertEquals(RecoveryStatus.COMPLETED,
                harness.recoveryService.complete("rf1").status());
    }

    @Test
    void expiredFailureIsStableAcrossRestartAndNewRequestCanSucceed() {
        MutableClock clock = MutableClock.startAt(91_000L);
        LifecycleHarness harness = recycled(clock, "f2");
        RecoveryChallenge challenge = harness.recoveryService.startRecovery("f2", "rf2");

        clock.advanceMillis(100);
        harness.scanCoordinator.scanAccount("f2");
        assertEquals(RecoveryStatus.FAILED, harness.store.getRecovery("rf2").status());

        LifecycleHarness reopened = harness.reopen();
        assertEquals(RecoveryStatus.FAILED, reopened.store.getRecovery("rf2").status());
        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("f2").state());
        assertEquals(0, reopened.scanCoordinator.scanOnce());
        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("f2").state());

        assertThrows(InvalidTransitionException.class,
                () -> reopened.recoveryService.verify("rf2", challenge.verificationToken()));

        RecoveryChallenge retry = reopened.recoveryService.startRecovery("f2", "rf2-retry");
        assertNotEquals("rf2", retry.recovery().requestId());
        reopened.recoveryService.verify("rf2-retry", retry.verificationToken());
        reopened.recoveryService.complete("rf2-retry");
        assertEquals(AccountState.ACTIVE, reopened.store.getAccount("f2").state());
    }
}
