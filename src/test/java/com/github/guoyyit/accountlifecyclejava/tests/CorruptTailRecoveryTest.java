package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.VerificationFailedException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorruptTailRecoveryTest {

    private static final long T0 = 900_000L;

    @TempDir
    Path tempDir;

    private LifecycleHarness newHarness(MutableClock clock) {
        return new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofMillis(50), Duration.ofHours(1)));
    }

    private Path logFile() {
        return tempDir.resolve("lifecycle.log");
    }

    private static void appendBytes(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private LifecycleHarness recycledAccountHarness() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = newHarness(clock);
        harness.accountService.register("a1", "default", null, null);
        clock.advanceMillis(100);
        harness.scanCoordinator.scanAccount("a1");
        clock.advanceMillis(50);
        harness.scanCoordinator.scanAccount("a1");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("a1").state());
        return harness;
    }

    @Test
    void tornTailRecordIsDiscardedAndCompletedHistorySurvives() {
        LifecycleHarness harness = recycledAccountHarness();
        long sequence = harness.log.lastSequence();
        byte[] validBytes = readBytes(logFile());

        appendBytes(logFile(),
                "{\"sequence\":99,\"timestampMillis\":1,\"type\":\"STATE_CHA"
                        .getBytes(StandardCharsets.UTF_8));

        LifecycleHarness reopened = harness.reopen();
        assertEquals(sequence, reopened.log.lastSequence());
        Account account = reopened.store.getAccount("a1");
        assertEquals(AccountState.RECYCLED, account.state());
        assertEquals(TransitionReason.DORMANT_BUFFER_RECYCLE, account.stateReason());
        assertArrayEquals(validBytes, readBytes(logFile()), "损坏尾部应被截断，完整前缀不变");

        reopened.accountService.register("b1", "default", null, null);
        assertEquals(sequence + 1, reopened.log.lastSequence());
        reopened.scanCoordinator.scanOnce();
        assertEquals(3,
                reopened.auditQueryService.query(new AuditQuery("a1", null, null)).size());
        assertEquals(sequence + 1,
                reopened.auditQueryService.query(new AuditQuery(null, null, null)).size());
    }

    @Test
    void truncationAtAnyCutPointRebuildsSameState() {
        LifecycleHarness harness = recycledAccountHarness();
        long sequence = harness.log.lastSequence();
        byte[] validBytes = readBytes(logFile());
        String fakeLine = "{\"sequence\":" + (sequence + 1)
                + ",\"timestampMillis\":1,\"type\":\"STATE_CHANGED\",\"accountId\":\"a1\","
                + "\"payloadJson\":\"{}\"}\n";
        byte[] fakeBytes = fakeLine.getBytes(StandardCharsets.UTF_8);

        for (int cut : new int[]{1, fakeBytes.length / 3, fakeBytes.length / 2,
                fakeBytes.length - 1}) {
            appendBytes(logFile(), java.util.Arrays.copyOf(fakeBytes, cut));
            LifecycleHarness reopened = harness.reopen();
            assertEquals(sequence, reopened.log.lastSequence(), "cut=" + cut);
            assertEquals(AccountState.RECYCLED, reopened.store.getAccount("a1").state());
            assertArrayEquals(validBytes, readBytes(logFile()), "cut=" + cut);
            harness = reopened;
        }
    }

    @Test
    void legacyTwoEventRenewalAndLegacyRecoveryStillReplay() {
        MutableClock clock = MutableClock.startAt(T0);
        LifecycleHarness harness = newHarness(clock);
        long now = clock.nowMillis();

        harness.log.append(EventType.ACCOUNT_REGISTERED, "old1", JsonCodec.toJson(
                new Payloads.AccountRegistered("old1", "default", now, 10L, null)));
        harness.log.append(EventType.STATE_CHANGED, "old1", JsonCodec.toJson(
                new Payloads.StateChanged("old1", AccountState.ACTIVE, AccountState.GRACE,
                        TransitionReason.CREDENTIAL_EXPIRED, now + 10, "凭据过期")));
        harness.log.append(EventType.CREDENTIAL_UPDATED, "old1", JsonCodec.toJson(
                new Payloads.CredentialUpdated("old1", now + 10 + 5_000, now + 11)));
        harness.log.append(EventType.STATE_CHANGED, "old1", JsonCodec.toJson(
                new Payloads.StateChanged("old1", AccountState.GRACE, AccountState.ACTIVE,
                        TransitionReason.CREDENTIAL_RENEWED, now + 11, "旧格式两事件更新")));
        harness.log.append(EventType.ACCOUNT_REGISTERED, "old2", JsonCodec.toJson(
                new Payloads.AccountRegistered("old2", "default", now, null, null)));
        harness.log.append(EventType.STATE_CHANGED, "old2", JsonCodec.toJson(
                new Payloads.StateChanged("old2", AccountState.ACTIVE, AccountState.RECYCLED,
                        TransitionReason.GRACE_TIMEOUT_RECYCLE, now + 20, "宽限超时")));
        harness.log.append(EventType.RECOVERY_STARTED, "old2",
                "{\"requestId\":\"legacy-req\",\"accountId\":\"old2\",\"ttlMillis\":3600000,"
                        + "\"atMillis\":" + (now + 21) + "}");

        LifecycleHarness reopened = harness.reopen();
        Account renewed = reopened.store.getAccount("old1");
        assertEquals(AccountState.ACTIVE, renewed.state());
        assertEquals(TransitionReason.CREDENTIAL_RENEWED, renewed.stateReason());
        assertEquals(now + 10 + 5_000, renewed.credentialExpiresAtMillis());

        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("old2").state());
        PendingRecovery legacy = reopened.store.getRecovery("legacy-req");
        assertEquals(RecoveryStatus.PENDING, legacy.status());
        assertNull(legacy.verificationToken());
        assertThrows(VerificationFailedException.class,
                () -> reopened.recoveryService.verify("legacy-req", "any-token"));
        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("old2").state());
    }
}
