package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTornTailTest {

    @TempDir
    Path tempDir;

    private Path logFile() {
        return tempDir.resolve("lifecycle.log");
    }

    private LifecycleHarness recycledDormantAccount(MutableClock clock) {
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofMillis(50), Duration.ofHours(1)));
        harness.accountService.register("t1", "default", null, null);
        clock.advanceMillis(100);
        harness.scanCoordinator.scanOnce();
        clock.advanceMillis(50);
        harness.scanCoordinator.scanOnce();
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("t1").state());
        return harness;
    }

    @Test
    void tornTailRecordIsDiscardedButAllCompleteRecordsRebuilt() throws Exception {
        MutableClock clock = MutableClock.startAt(60_000L);
        LifecycleHarness harness = recycledDormantAccount(clock);
        long completeEvents = harness.log.lastSequence();
        byte[] intact = Files.readAllBytes(logFile());

        Files.write(logFile(), new byte[]{'{', '"', 's'}, StandardOpenOption.APPEND);
        Files.write(logFile(), "partial-line-without-newline".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        LifecycleHarness reopened = harness.reopen();
        assertEquals(completeEvents, reopened.log.lastSequence());
        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("t1").state());

        byte[] repaired = Files.readAllBytes(logFile());
        assertEquals(intact.length, repaired.length, "尾部不完整记录必须被物理截断");
        assertEquals(new String(intact, StandardCharsets.UTF_8),
                new String(repaired, StandardCharsets.UTF_8));
    }

    @Test
    void tornTailAfterNewlineIsDiscardedOnEveryRestart() throws Exception {
        MutableClock clock = MutableClock.startAt(61_000L);
        LifecycleHarness harness = recycledDormantAccount(clock);
        long completeEvents = harness.log.lastSequence();

        Files.write(logFile(), "\n{\"sequence\":999,\"damaged".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        LifecycleHarness first = harness.reopen();
        assertEquals(completeEvents, first.log.lastSequence());
        assertTrue(first.log.tornTailBytesDiscarded() > 0);

        LifecycleHarness second = first.reopen();
        assertEquals(completeEvents, second.log.lastSequence());
        assertEquals(0, second.log.tornTailBytesDiscarded(), "修复后重启不再报告丢弃");
        assertEquals(AccountState.RECYCLED, second.store.getAccount("t1").state());

        List<AuditEntry> audit = second.auditQueryService.byAccount("t1");
        long maxAuditSequence = audit.stream().mapToLong(AuditEntry::sequence).max().orElse(0);
        assertTrue(maxAuditSequence <= completeEvents);
    }

    @Test
    void tornTailDoesNotReapplyCompletedEvents() {
        MutableClock clock = MutableClock.startAt(62_000L);
        LifecycleHarness harness = recycledDormantAccount(clock);
        long completeEvents = harness.log.lastSequence();

        LifecycleHarness reopened = harness.reopen();
        assertEquals(completeEvents, reopened.log.lastSequence());
        assertEquals(0, reopened.scanCoordinator.scanOnce());
        assertEquals(AccountState.RECYCLED, reopened.store.getAccount("t1").state());
    }

    @Test
    void corruptLineInTheMiddleIsNotSilentlyIgnored() throws Exception {
        MutableClock clock = MutableClock.startAt(63_000L);
        LifecycleHarness harness = recycledDormantAccount(clock);
        byte[] intact = Files.readAllBytes(logFile());

        byte[] corrupted = intact.clone();
        int cut = intact.length / 2;
        System.arraycopy("<<<CORRUPT>>>".getBytes(StandardCharsets.UTF_8), 0,
                corrupted, cut, 13);
        Files.write(logFile(), corrupted, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(LifecycleException.class,
                () -> new DurableLog(tempDir, clock));
    }
}
