package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicRenewalCrashTest {

    private static final long T0 = 1_100_000L;
    private static final Duration CREDENTIAL_TTL = Duration.ofMillis(10);
    private static final Duration GRACE = Duration.ofMillis(50);
    private static final Duration RENEW_TTL = Duration.ofDays(1);

    @TempDir
    Path tempDir;

    private LifecycleHarness graceHarness(MutableClock clock, String accountId) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), GRACE,
                        Duration.ofDays(10), Duration.ofHours(1)));
        harness.accountService.register(accountId, "default", CREDENTIAL_TTL, null);
        clock.advanceMillis(CREDENTIAL_TTL.toMillis());
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());
        return harness;
    }

    @Test
    void crashBeforePersistLeavesRenewalFullyUnapplied() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceHarness(clock, "x1");
        Account before = harness.store.getAccount("x1");
        long sequenceBefore = harness.log.lastSequence();

        harness.log.setCrashBeforeFsync(t -> t == EventType.CREDENTIAL_RENEWED);
        assertThrows(LifecycleException.class,
                () -> harness.credentialService.renew("x1", RENEW_TTL));
        harness.log.clearFaults();

        assertEquals(sequenceBefore, harness.log.lastSequence());

        LifecycleHarness reopened = harness.reopen();
        Account account = reopened.store.getAccount("x1");
        assertEquals(AccountState.GRACE, account.state());
        assertEquals(before.credentialExpiresAtMillis(), account.credentialExpiresAtMillis());
        assertEquals(before.stateEnteredAtMillis(), account.stateEnteredAtMillis());

        long deadline = account.stateEnteredAtMillis() + GRACE.toMillis();
        clock.setMillis(deadline);
        reopened.scanCoordinator.scanAccount("x1");
        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("x1").state());
    }

    @Test
    void crashAfterPersistAppliesRenewalFullyAfterRestart() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceHarness(clock, "x2");
        long submittedAt = clock.nowMillis();

        harness.log.setCrashAfterFsync(t -> t == EventType.CREDENTIAL_RENEWED);
        assertThrows(LifecycleException.class,
                () -> harness.credentialService.renew("x2", RENEW_TTL));

        LifecycleHarness reopened = harness.reopen();
        Account account = reopened.store.getAccount("x2");
        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED, account.stateReason());
        assertEquals(submittedAt + RENEW_TTL.toMillis(), account.credentialExpiresAtMillis());
        assertEquals(submittedAt, account.stateEnteredAtMillis());

        long renewedEvents = reopened.auditQueryService
                .query(new AuditQuery("x2", null, null)).stream()
                .filter(e -> "CREDENTIAL_RENEWED".equals(e.eventType()))
                .count();
        assertEquals(1, renewedEvents, "有效期与状态恢复由同一原子事件承载");

        assertEquals(0, reopened.scanCoordinator.scanOnce());
        clock.advanceMillis(GRACE.toMillis() * 10);
        reopened.credentialService.renew("x2", RENEW_TTL);
        assertEquals(AccountState.ACTIVE, reopened.store.getAccount("x2").state());
    }

    @Test
    void tornRenewedRecordAtTailIsFullyDiscarded() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = graceHarness(clock, "x3");
        Account before = harness.store.getAccount("x3");

        try {
            Files.write(tempDir.resolve("lifecycle.log"),
                    "{\"sequence\":77,\"timestampMillis\":1,\"type\":\"CREDENTIAL_RENEWED\","
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        LifecycleHarness reopened = harness.reopen();
        Account account = reopened.store.getAccount("x3");
        assertEquals(AccountState.GRACE, account.state());
        assertEquals(before.credentialExpiresAtMillis(), account.credentialExpiresAtMillis());

        reopened.credentialService.renew("x3", RENEW_TTL);
        assertEquals(AccountState.ACTIVE, reopened.store.getAccount("x3").state());
    }
}
