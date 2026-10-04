package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
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

class GraceDeadlineTest {

    @TempDir
    Path tempDir;

    private LifecycleHarness graceOf50(MutableClock clock) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofHours(1)));
        harness.accountService.register("g1", "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount("g1");
        assertEquals(AccountState.GRACE, harness.store.getAccount("g1").state());
        return harness;
    }

    @Test
    void renewJustBeforeDeadlineRestoresActive() {
        MutableClock clock = MutableClock.startAt(1_000L);
        LifecycleHarness harness = graceOf50(clock);

        clock.advanceMillis(49);
        harness.credentialService.renew("g1", Duration.ofMillis(1_000));
        Account restored = harness.store.getAccount("g1");
        assertEquals(AccountState.ACTIVE, restored.state());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED, restored.stateReason());
        assertEquals(clock.nowMillis() + 1_000L, restored.credentialExpiresAtMillis());

        assertEquals(false, harness.scanCoordinator.scanAccount("g1"));
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("g1").state());
    }

    @Test
    void renewAtDeadlineIsRejectedAndAccountUntouched() {
        MutableClock clock = MutableClock.startAt(2_000L);
        LifecycleHarness harness = graceOf50(clock);

        long credentialExpiryBefore = harness.store.getAccount("g1").credentialExpiresAtMillis();
        long versionBefore = harness.store.getAccount("g1").version();

        clock.advanceMillis(50);
        GracePeriodExpiredException ex = assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g1", Duration.ofMillis(1_000)));
        assertEquals(GracePeriodExpiredException.CODE, ex.code());

        Account unchanged = harness.store.getAccount("g1");
        assertEquals(AccountState.GRACE, unchanged.state());
        assertEquals(credentialExpiryBefore, unchanged.credentialExpiresAtMillis());
        assertEquals(versionBefore, unchanged.version());

        harness.scanCoordinator.scanAccount("g1");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("g1").state());
        assertEquals(TransitionReason.GRACE_TIMEOUT_RECYCLE,
                harness.store.getAccount("g1").stateReason());
    }

    @Test
    void scanFirstAtDeadlineThenRenewStillRejected() {
        MutableClock clock = MutableClock.startAt(3_000L);
        LifecycleHarness harness = graceOf50(clock);

        clock.advanceMillis(50);
        harness.scanCoordinator.scanAccount("g1");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("g1").state());

        assertThrows(InvalidTransitionException.class,
                () -> harness.credentialService.renew("g1", Duration.ofMillis(1_000)));
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("g1").state());
    }

    @Test
    void rejectionReasonIsDistinguishableFromMissingAccountAndWrongState() {
        MutableClock clock = MutableClock.startAt(4_000L);
        LifecycleHarness harness = graceOf50(clock);

        clock.advanceMillis(50);
        assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g1", Duration.ofMillis(1)));
        assertThrows(LifecycleException.class,
                () -> harness.credentialService.renew("missing", Duration.ofMillis(1)));

        harness.scanCoordinator.scanAccount("g1");
        assertThrows(InvalidTransitionException.class,
                () -> harness.credentialService.renew("g1", Duration.ofMillis(1)));

        List<AuditEntry> entries = harness.auditQueryService.byAccount("g1");
        List<String> reasons = entries.stream()
                .filter(e -> e.eventType().equals(EventType.CREDENTIAL_RENEWAL_REJECTED.name()))
                .map(AuditEntry::detail)
                .toList();
        assertEquals(2, reasons.size());
        assertReason(reasons.get(0), "GRACE_PERIOD_EXPIRED");
        assertReason(reasons.get(1), "STATE_NOT_ALLOWED");

        List<AuditEntry> missingRejections = harness.auditQueryService.byAccount("missing");
        assertEquals(1, missingRejections.size());
        assertReason(missingRejections.get(0).detail(), "ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectedRenewDoesNotChangeCredentialAfterScan() {
        MutableClock clock = MutableClock.startAt(5_000L);
        LifecycleHarness harness = graceOf50(clock);
        long expiryBefore = harness.store.getAccount("g1").credentialExpiresAtMillis();

        clock.advanceMillis(50);
        assertThrows(GracePeriodExpiredException.class,
                () -> harness.credentialService.renew("g1", Duration.ofDays(30)));
        assertEquals(expiryBefore, harness.store.getAccount("g1").credentialExpiresAtMillis());
    }

    private static void assertReason(String detail, String expected) {
        org.junit.jupiter.api.Assertions.assertTrue(detail.contains(expected),
                "审计记录应包含拒绝原因 " + expected + "，实际: " + detail);
    }
}
