package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenewalAtomicityTest {

    @TempDir
    Path tempDir;

    private LifecycleHarness enterGrace(MutableClock clock, String accountId) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofDays(100), Duration.ofMillis(50),
                        Duration.ofDays(10), Duration.ofHours(1)));
        harness.accountService.register(accountId, "default", Duration.ofMillis(10), null);
        clock.advanceMillis(10);
        harness.scanCoordinator.scanAccount(accountId);
        assertEquals(AccountState.GRACE, harness.store.getAccount(accountId).state());
        return harness;
    }

    @Test
    void crashBeforeRenewalDurableLeavesNoHalfAppliedEffect() {
        MutableClock clock = MutableClock.startAt(70_000L);
        LifecycleHarness harness = enterGrace(clock, "a1");
        long before = harness.log.lastSequence();
        Long expiryBefore = harness.store.getAccount("a1").credentialExpiresAtMillis();

        harness.log.setCrashBeforeFsync(t -> t == EventType.CREDENTIAL_RENEWED);
        assertThrows(LifecycleException.class,
                () -> harness.credentialService.renew("a1", Duration.ofMillis(1_000)));
        harness.log.clearFaults();

        assertEquals(before, harness.log.lastSequence());
        assertEquals(AccountState.GRACE, harness.store.getAccount("a1").state());
        assertEquals(expiryBefore, harness.store.getAccount("a1").credentialExpiresAtMillis());

        LifecycleHarness reopened = harness.reopen();
        assertEquals(before, reopened.log.lastSequence());
        assertEquals(AccountState.GRACE, reopened.store.getAccount("a1").state());
        assertEquals(expiryBefore, reopened.store.getAccount("a1").credentialExpiresAtMillis());

        reopened.credentialService.renew("a1", Duration.ofMillis(1_000));
        assertEquals(AccountState.ACTIVE, reopened.store.getAccount("a1").state());
    }

    @Test
    void crashAfterRenewalDurableAppliesBothEffectsAtomically() {
        MutableClock clock = MutableClock.startAt(71_000L);
        LifecycleHarness harness = enterGrace(clock, "a2");

        harness.log.setCrashAfterFsync(t -> t == EventType.CREDENTIAL_RENEWED);
        assertThrows(LifecycleException.class,
                () -> harness.credentialService.renew("a2", Duration.ofMillis(1_000)));
        long durable = harness.log.lastSequence();
        harness.log.clearFaults();

        LifecycleHarness reopened = harness.reopen();
        assertEquals(durable, reopened.log.lastSequence());
        Account account = reopened.store.getAccount("a2");
        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(clock.nowMillis() + 1_000L, account.credentialExpiresAtMillis());

        assertFalse(reopened.scanCoordinator.scanAccount("a2"));
        assertEquals(AccountState.ACTIVE, reopened.store.getAccount("a2").state());
    }

    @Test
    void tornTailOfRenewalEventRebuildsAsFullyNotEffective() throws Exception {
        MutableClock clock = MutableClock.startAt(72_000L);
        LifecycleHarness harness = enterGrace(clock, "a3");
        long before = harness.log.lastSequence();
        Long expiryBefore = harness.store.getAccount("a3").credentialExpiresAtMillis();

        byte[] intact = Files.readAllBytes(tempDir.resolve("lifecycle.log"));
        Files.write(tempDir.resolve("lifecycle.log"),
                "{\"sequence\":999,\"type\":\"CREDENTIAL_RENEWED\",\"damaged".getBytes(
                        StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        LifecycleHarness reopened = harness.reopen();
        assertEquals(before, reopened.log.lastSequence());
        Account account = reopened.store.getAccount("a3");
        assertEquals(AccountState.GRACE, account.state(), "不能出现有效期已更新但状态停留在宽限");
        assertEquals(expiryBefore, account.credentialExpiresAtMillis());

        byte[] repaired = Files.readAllBytes(tempDir.resolve("lifecycle.log"));
        assertEquals(intact.length, repaired.length);
    }
}
