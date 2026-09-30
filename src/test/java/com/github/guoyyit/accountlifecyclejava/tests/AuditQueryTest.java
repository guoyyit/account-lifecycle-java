package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditQueryTest {

    @TempDir
    Path tempDir;

    @Test
    void auditTrailReconstructsStateEvolution() {
        MutableClock clock = MutableClock.startAt(110_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofMillis(50), Duration.ofHours(1)));
        long t0 = clock.nowMillis();

        harness.accountService.register("a1", "default", null, null);
        harness.policyService.putPolicy("default", Duration.ofMillis(50), Duration.ofDays(1),
                Duration.ofMillis(50), Duration.ZERO, Duration.ofHours(1), "缩短休眠阈值");

        clock.advanceMillis(100);
        harness.scanCoordinator.scanAccount("a1");
        clock.advanceMillis(50);
        harness.scanCoordinator.scanAccount("a1");
        assertEquals(AccountState.RECYCLED, harness.store.getAccount("a1").state());

        harness.recoveryService.startRecovery("a1", "ar-1");
        harness.recoveryService.verify("ar-1", false, "验证失败");
        harness.recoveryService.startRecovery("a1", "ar-2");
        harness.recoveryService.verify("ar-2", true, null);
        harness.recoveryService.complete("ar-2");

        List<AuditEntry> entries = harness.auditQueryService.byAccount("a1");
        List<String> types = entries.stream().map(AuditEntry::eventType).toList();
        assertTrue(types.contains("ACCOUNT_REGISTERED"));
        assertTrue(types.contains("STATE_CHANGED"));
        assertTrue(types.contains("RECOVERY_STARTED"));
        assertTrue(types.contains("RECOVERY_FAILED"));
        assertTrue(types.contains("RECOVERY_VERIFIED"));
        assertTrue(types.contains("RECOVERY_COMPLETED"));

        List<AuditEntry> policyEntries = harness.auditQueryService
                .query(new AuditQuery(null, null, null)).stream()
                .filter(e -> "POLICY_PUT".equals(e.eventType()))
                .toList();
        assertTrue(policyEntries.size() >= 2);

        List<AuditEntry> windowed = harness.auditQueryService
                .query(new AuditQuery("a1", t0, t0 + 99));
        assertEquals(1, windowed.size(), windowed.toString());
        assertEquals("ACCOUNT_REGISTERED", windowed.get(0).eventType());

        List<AuditEntry> all = harness.auditQueryService.query(new AuditQuery(null, null, null));
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).sequence() < all.get(i).sequence());
        }

        long firstSequence = entries.get(0).sequence();
        LifecycleHarness reopened = harness.reopen();
        assertEquals(firstSequence, reopened.auditQueryService.byAccount("a1").get(0).sequence());
    }
}
