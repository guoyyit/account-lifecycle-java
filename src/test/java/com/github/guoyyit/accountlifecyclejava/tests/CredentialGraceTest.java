package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialGraceTest {

    @TempDir
    Path tempDir;

    @Test
    void credentialExpiresAtExactInstantThenGraceRecoveryAndTimeout() {
        MutableClock clock = MutableClock.startAt(10_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofHours(1)));

        harness.accountService.register("c1", "default", Duration.ofMillis(100), null);

        clock.advanceMillis(99);
        harness.scanCoordinator.scanAccount("c1");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("c1").state());

        clock.advanceMillis(1);
        harness.scanCoordinator.scanAccount("c1");
        Account inGrace = harness.store.getAccount("c1");
        assertEquals(AccountState.GRACE, inGrace.state());
        assertEquals(TransitionReason.CREDENTIAL_EXPIRED, inGrace.stateReason());

        clock.advanceMillis(40);
        harness.scanCoordinator.scanAccount("c1");
        assertEquals(AccountState.GRACE, harness.store.getAccount("c1").state());

        harness.credentialService.renew("c1", Duration.ofMillis(1_000));
        Account restored = harness.store.getAccount("c1");
        assertEquals(AccountState.ACTIVE, restored.state());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED, restored.stateReason());
    }

    @Test
    void graceTimeoutRecyclesAtExactBoundary() {
        MutableClock clock = MutableClock.startAt(20_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofHours(1)));

        harness.accountService.register("c2", "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("c2");
        assertEquals(AccountState.GRACE, harness.store.getAccount("c2").state());

        clock.advanceMillis(49);
        harness.scanCoordinator.scanAccount("c2");
        assertEquals(AccountState.GRACE, harness.store.getAccount("c2").state());

        clock.advanceMillis(1);
        harness.scanCoordinator.scanAccount("c2");
        Account recycled = harness.store.getAccount("c2");
        assertEquals(AccountState.RECYCLED, recycled.state());
        assertEquals(TransitionReason.GRACE_TIMEOUT_RECYCLE, recycled.stateReason());

        assertThrows(InvalidTransitionException.class,
                () -> harness.credentialService.renew("c2", Duration.ofDays(1)));
    }
}
