package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DormancyBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void boundaryIsInclusiveAndDeterministic() {
        MutableClock clock = MutableClock.startAt(1_000_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofDays(10), Duration.ofHours(1)));

        harness.accountService.register("u1", "default", null, null);

        clock.advanceMillis(99);
        assertEquals(0, harness.scanCoordinator.scanOnce());
        Account before = harness.store.getAccount("u1");
        assertEquals(AccountState.ACTIVE, before.state());

        clock.advanceMillis(1);
        assertEquals(1, harness.scanCoordinator.scanOnce());
        Account atBoundary = harness.store.getAccount("u1");
        assertEquals(AccountState.DORMANT, atBoundary.state());
        assertEquals(TransitionReason.DORMANCY_SCAN, atBoundary.stateReason());
        assertEquals(clock.nowMillis(), atBoundary.stateEnteredAtMillis());

        assertEquals(0, harness.scanCoordinator.scanOnce());
    }

    @Test
    void freshActivityResetsDormancyClock() {
        MutableClock clock = MutableClock.startAt(2_000_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofDays(10), Duration.ofHours(1)));

        harness.accountService.register("u2", "default", null, null);
        clock.advanceMillis(90);
        harness.activityService.report("u2");
        clock.advanceMillis(90);

        assertEquals(0, harness.scanCoordinator.scanOnce());
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("u2").state());
    }
}
