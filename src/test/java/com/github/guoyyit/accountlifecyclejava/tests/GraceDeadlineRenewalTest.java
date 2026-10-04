package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.GracePeriodExpiredException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraceDeadlineRenewalTest {

    private static final long T0 = 500_000L;
    private static final Duration CREDENTIAL_TTL = Duration.ofMillis(10);
    private static final Duration GRACE = Duration.ofMillis(50);

    @TempDir
    Path tempDir;

    private LifecycleHarness newHarness(MutableClock clock) {
        return new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), GRACE,
                        Duration.ofDays(10), Duration.ofHours(1)));
    }

    private LifecycleHarness graceAccount(String accountId, MutableClock clock) {
        LifecycleHarness harness = newHarness(clock);
        harness.accountService.register(accountId, "default", CREDENTIAL_TTL, null);
        clock.advanceMillis(CREDENTIAL_TTL.toMillis());
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());
        return harness;
    }

    private static long deadlineOf(LifecycleHarness harness, String accountId) {
        return harness.store.getAccount(accountId).stateEnteredAtMillis() + GRACE.toMillis();
    }

    @Test
    void renewalOneMillisBeforeDeadlineStillRecovers() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceAccount("g1", clock);
        long deadline = deadlineOf(harness, "g1");

        clock.setMillis(deadline - 1);
        harness.credentialService.renew("g1", Duration.ofDays(1));

        Account account = harness.store.getAccount("g1");
        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED, account.stateReason());
        assertEquals(deadline - 1 + Duration.ofDays(1).toMillis(),
                account.credentialExpiresAtMillis());

        clock.setMillis(deadline + 10_000);
        harness.scanCoordinator.scanAccount("g1");
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("g1").state());
    }

    @Test
    void renewalAtDeadlineIsRejectedWithoutAnyChange() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceAccount("g2", clock);
        long deadline = deadlineOf(harness, "g2");
        Long expiresBefore = harness.store.getAccount("g2").credentialExpiresAtMillis();

        clock.setMillis(deadline);
        assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g2", Duration.ofMillis(1_000)));

        Account account = harness.store.getAccount("g2");
        assertEquals(AccountState.GRACE, account.state());
        assertEquals(TransitionReason.CREDENTIAL_EXPIRED, account.stateReason());
        assertEquals(expiresBefore, account.credentialExpiresAtMillis());

        harness.scanCoordinator.scanAccount("g2");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("g2").state());
    }

    @Test
    void renewalAfterDeadlineIsRejectedEvenWhenScanHasNotRun() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceAccount("g3", clock);
        long deadline = deadlineOf(harness, "g3");

        clock.setMillis(deadline + 5_000);
        assertEquals(AccountState.GRACE, harness.store.getAccount("g3").state());
        assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g3", Duration.ofMillis(1_000)));
        assertEquals(AccountState.GRACE, harness.store.getAccount("g3").state());

        harness.scanCoordinator.scanAccount("g3");
        Account recycled = harness.store.getAccount("g3");
        assertEquals(AccountState.RECYCLED, recycled.state());
        assertEquals(TransitionReason.GRACE_TIMEOUT_RECYCLE, recycled.stateReason());
    }

    @Test
    void outcomeAtDeadlineIsIndependentOfRenewalAndScanOrder() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceAccount("g4", clock);
        long deadline = deadlineOf(harness, "g4");

        clock.setMillis(deadline);
        assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g4", Duration.ofMillis(1_000)));
        harness.scanCoordinator.scanAccount("g4");
        Account renewFirst = harness.store.getAccount("g4");

        LifecycleHarness harness2 = graceAccount("g5", clock);
        clock.setMillis(deadlineOf(harness2, "g5"));
        harness2.scanCoordinator.scanAccount("g5");
        assertThrows(InvalidTransitionException.class,
                () -> harness2.credentialService.renew("g5", Duration.ofMillis(1_000)));
        Account scanFirst = harness2.store.getAccount("g5");

        assertEquals(renewFirst.state(), scanFirst.state());
        assertEquals(AccountState.RECYCLED, renewFirst.state());
        assertEquals(renewFirst.stateReason(), scanFirst.stateReason());
        assertEquals(TransitionReason.GRACE_TIMEOUT_RECYCLE, renewFirst.stateReason());
    }

    @Test
    void rejectedRenewalsAreAuditedWithDistinctReasons() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceAccount("g6", clock);
        long deadline = deadlineOf(harness, "g6");

        clock.setMillis(deadline);
        assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g6", Duration.ofMillis(1_000)));
        assertThrows(LifecycleException.class,
                () -> harness.credentialService.renew("ghost", Duration.ofMillis(1_000)));

        harness.scanCoordinator.scanAccount("g6");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("g6").state());
        assertThrows(InvalidTransitionException.class,
                () -> harness.credentialService.renew("g6", Duration.ofMillis(1_000)));

        List<AuditEntry> rejected = harness.auditQueryService
                .query(new com.github.guoyyit.accountlifecyclejava.model.AuditQuery(null, null, null))
                .stream()
                .filter(e -> "CREDENTIAL_RENEWAL_REJECTED".equals(e.eventType()))
                .toList();
        assertEquals(3, rejected.size());
        assertTrue(rejected.stream().anyMatch(e -> "g6".equals(e.accountId())
                && e.detail().contains("GRACE_PERIOD_EXPIRED")));
        assertTrue(rejected.stream().anyMatch(e -> "ghost".equals(e.accountId())
                && e.detail().contains("ACCOUNT_NOT_FOUND")));
        assertTrue(rejected.stream().anyMatch(e -> "g6".equals(e.accountId())
                && e.detail().contains("STATE_NOT_ALLOWED")));
    }
}
