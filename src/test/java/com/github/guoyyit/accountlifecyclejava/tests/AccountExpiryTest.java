package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountExpiryTest {

    @TempDir
    Path tempDir;

    @Test
    void accountLifetimeExpiryDisablesAtBoundaryAndStaysStable() {
        MutableClock clock = MutableClock.startAt(120_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(1), Duration.ofDays(1),
                        Duration.ofDays(1), Duration.ofHours(1)));

        harness.accountService.register("e1", "default", null, Duration.ofMillis(30));

        clock.advanceMillis(29);
        harness.scanCoordinator.scanAccount("e1");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("e1").state());

        clock.advanceMillis(1);
        harness.scanCoordinator.scanAccount("e1");
        assertEquals(AccountState.DISABLED, harness.store.getAccount("e1").state());
        assertEquals(TransitionReason.ACCOUNT_EXPIRED,
                harness.store.getAccount("e1").stateReason());

        clock.advanceMillis(10_000);
        harness.scanCoordinator.scanAccount("e1");
        assertEquals(AccountState.DISABLED, harness.store.getAccount("e1").state());
    }

    @Test
    void adminCanReEnableExpiredAccount() {
        MutableClock clock = MutableClock.startAt(130_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(1), Duration.ofDays(1),
                        Duration.ofDays(1), Duration.ofHours(1)));

        harness.accountService.register("e2", "default", null, Duration.ofMillis(10));
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("e2");
        assertEquals(AccountState.DISABLED, harness.store.getAccount("e2").state());

        harness.accountService.forceTransition("e2", AccountState.ACTIVE,
                TransitionReason.REACTIVATED, "管理员手动启用");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("e2").state());
        assertEquals(TransitionReason.REACTIVATED, harness.store.getAccount("e2").stateReason());
    }
}
