package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.error.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 凭据与账号双重过期约束及临界语义：
 * at == expiresAt 凭据仍有效；宽限期 at == graceDeadline 仍可更新。
 */
class CredentialGraceTest {

    @TempDir
    Path tempDir;

    @Test
    void credentialStillValidAtExactExpiryInstant() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-boundary");
        Instant expiresAt = account.getCredentialExpiresAt();

        // 凭据有效期(365d)长于休眠阈值(90d)：到期前持续上报活动，排除休眠干扰。
        harness.activityService.report("c-boundary",
                expiresAt.minusSeconds(1), "keep-active");
        harness.setTime(expiresAt);
        harness.scanner.scanOnce(expiresAt);

        assertEquals(AccountStatus.ACTIVE,
                harness.accountService.get("c-boundary").getStatus());
    }

    @Test
    void entersGraceOneSecondAfterExpiry() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-grace");
        Instant expired = account.getCredentialExpiresAt().plusSeconds(1);

        harness.keepActiveAt("c-grace", expired);
        harness.setTime(expired);
        harness.scanner.scanOnce(expired);

        Account reloaded = harness.accountService.get("c-grace");
        assertEquals(AccountStatus.GRACE, reloaded.getStatus());
        assertEquals(TransitionReason.CREDENTIAL_EXPIRED,
                reloaded.getStatusReason());
    }

    @Test
    void renewWithinGraceRestoresActiveWithNewCredential() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-renew");
        Instant graceStart = account.getCredentialExpiresAt().plusSeconds(1);
        harness.keepActiveAt("c-renew", graceStart);
        harness.scanner.scanOnce(graceStart);

        harness.setTime(graceStart.plus(Duration.ofDays(3)));
        Account renewed =
                harness.credentialService.renewCredential("c-renew", "renew-1");

        assertEquals(AccountStatus.ACTIVE, renewed.getStatus());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED,
                renewed.getStatusReason());
        assertEquals(harness.now().plus(Duration.ofDays(365)),
                renewed.getCredentialExpiresAt());
    }

    @Test
    void renewalAtExactGraceDeadlineStillSucceeds() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-deadline");
        Instant graceStart = account.getCredentialExpiresAt().plusSeconds(1);
        harness.keepActiveAt("c-deadline", graceStart);
        harness.scanner.scanOnce(graceStart);

        // 进入宽限后恰好 7 天整：仍在宽限期内，允许更新。
        Instant deadline = graceStart.plus(Duration.ofDays(7));
        harness.setTime(deadline);
        Account renewed =
                harness.credentialService.renewCredential("c-deadline", "renew-d");
        assertEquals(AccountStatus.ACTIVE, renewed.getStatus());
    }

    @Test
    void graceTimeoutRecyclesAccount() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-timeout");
        Instant graceStart = account.getCredentialExpiresAt().plusSeconds(1);
        harness.keepActiveAt("c-timeout", graceStart);
        harness.scanner.scanOnce(graceStart);

        Instant timeout = graceStart.plus(Duration.ofDays(7)).plusSeconds(1);
        harness.setTime(timeout);
        harness.scanner.scanOnce(timeout);

        Account reloaded = harness.accountService.get("c-timeout");
        assertEquals(AccountStatus.RECYCLED, reloaded.getStatus());
        assertEquals(TransitionReason.GRACE_TIMEOUT_RECYCLED,
                reloaded.getStatusReason());
    }

    @Test
    void renewalAfterGraceDeadlineIsRejectedAndAccountStaysRecycled() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-late");
        Instant graceStart = account.getCredentialExpiresAt().plusSeconds(1);
        harness.keepActiveAt("c-late", graceStart);
        harness.scanner.scanOnce(graceStart);

        Instant late = graceStart.plus(Duration.ofDays(7)).plusSeconds(1);
        harness.setTime(late);
        assertThrows(LifecycleException.class, () ->
                harness.credentialService.renewCredential("c-late", "renew-late"));
        assertEquals(AccountStatus.RECYCLED,
                harness.accountService.get("c-late").getStatus());
    }

    @Test
    void credentialRenewalIsIdempotent() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.register("c-idem");
        Instant graceStart = account.getCredentialExpiresAt().plusSeconds(1);
        harness.keepActiveAt("c-idem", graceStart);
        harness.scanner.scanOnce(graceStart);

        harness.setTime(graceStart.plus(Duration.ofDays(1)));
        Account first =
                harness.credentialService.renewCredential("c-idem", "same-key");
        Account second =
                harness.credentialService.renewCredential("c-idem", "same-key");

        assertEquals(first.getCredentialExpiresAt(), second.getCredentialExpiresAt());
        assertEquals(first.getVersion(), second.getVersion());
    }
}
