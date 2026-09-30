package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestartRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void replayRebuildsStateAfterRestart() {
        MutableClock clock = MutableClock.startAt(70_000L);
        LifecycleHarness first = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofMillis(50), Duration.ofHours(1)));
        first.accountService.register("p1", "default", null, null);
        clock.advanceMillis(100);
        first.scanCoordinator.scanOnce();
        clock.advanceMillis(50);
        first.scanCoordinator.scanOnce();
        assertEquals(AccountState.RECYCLED, first.store.getAccount("p1").state());

        LifecycleHarness second = first.reopen();
        assertEquals(AccountState.RECYCLED, second.store.getAccount("p1").state());
        long expectedSequence = second.log.lastSequence();

        LifecycleHarness third = second.reopen();
        assertEquals(expectedSequence, third.log.lastSequence());
        assertEquals(AccountState.RECYCLED, third.store.getAccount("p1").state());
    }

    @Test
    void crashBeforeFsyncLeavesNoTraceAndRetrySucceeds() {
        MutableClock clock = MutableClock.startAt(80_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(1), Duration.ofDays(1),
                        Duration.ofDays(1), Duration.ofHours(1)));
        harness.accountService.register("p2", "default", null, null);

        long sequenceBefore = harness.log.lastSequence();
        harness.log.setCrashBeforeFsync(t -> t == EventType.ACTIVITY_REPORTED);
        assertThrows(LifecycleException.class, () -> harness.activityService.report("p2"));
        harness.log.clearFaults();

        assertEquals(sequenceBefore, harness.log.lastSequence());
        assertEquals(80_000L, harness.store.getAccount("p2").lastActivityAtMillis());

        harness.activityService.report("p2");
        assertEquals(sequenceBefore + 1, harness.log.lastSequence());

        LifecycleHarness reopened = harness.reopen();
        assertEquals(sequenceBefore + 1, reopened.log.lastSequence());
    }

    @Test
    void crashAfterFsyncIsRecoveredWithoutDuplicate() {
        MutableClock clock = MutableClock.startAt(90_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(10), Duration.ofDays(1),
                        Duration.ofDays(1), Duration.ofHours(1)));
        harness.accountService.register("p3", "default", null, null);

        clock.advanceMillis(10);
        harness.log.setCrashAfterFsync(t -> t == EventType.STATE_CHANGED);
        assertThrows(LifecycleException.class, harness.scanCoordinator::scanOnce);
        long durableSequence = harness.log.lastSequence();

        LifecycleHarness reopened = harness.reopen();
        assertEquals(durableSequence, reopened.log.lastSequence());
        assertEquals(AccountState.DORMANT, reopened.store.getAccount("p3").state());

        reopened.log.clearFaults();
        assertEquals(0, reopened.scanCoordinator.scanOnce());
        assertEquals(durableSequence, reopened.log.lastSequence());
    }

    @Test
    void recoveryCompletionIsAtomicAcrossRestart() {
        MutableClock clock = MutableClock.startAt(100_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(10), Duration.ofDays(1),
                        Duration.ofMillis(10), Duration.ofHours(1)));
        harness.accountService.register("p4", "default", null, null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanOnce();
        clock.advanceMillis(10);
        harness.scanCoordinator.scanOnce();
        harness.recoveryService.startRecovery("p4", "req-p4");
        harness.recoveryService.verify("req-p4", true, null);

        harness.log.setCrashAfterFsync(t -> t == EventType.RECOVERY_COMPLETED);
        assertThrows(LifecycleException.class,
                () -> harness.recoveryService.complete("req-p4"));

        LifecycleHarness reopened = harness.reopen();
        assertEquals(AccountState.ACTIVE, reopened.store.getAccount("p4").state());
        assertEquals(RecoveryStatus.COMPLETED,
                reopened.store.getRecovery("req-p4").status());
    }
}
