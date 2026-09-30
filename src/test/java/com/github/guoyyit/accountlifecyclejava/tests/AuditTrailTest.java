package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTrailTest {

    @TempDir
    Path tempDir;

    @Test
    void auditTrailReconstructsFullEvolution() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("audit-1");
        Instant dormantAt = TestHarness.T0.plus(Duration.ofDays(91));
        harness.scanner.scanOnce(dormantAt);
        Instant recycledAt = dormantAt.plus(Duration.ofDays(31));
        harness.scanner.scanOnce(recycledAt);

        var session = harness.recoveryService
                .requestRecovery("audit-1", "audit-req");
        harness.recoveryService.completeRecovery(
                session.getSessionId(), session.getVerificationCode());

        List<LifecycleEvent> events = harness.audit("audit-1");
        List<EventType> types = events.stream().map(LifecycleEvent::type).toList();

        assertTrue(types.contains(EventType.ACCOUNT_REGISTERED));
        assertTrue(types.contains(EventType.STATE_TRANSITION));
        assertTrue(types.contains(EventType.RECOVERY_REQUESTED));
        assertTrue(types.contains(EventType.RECOVERY_COMPLETED));
        assertFalse(types.contains(EventType.RECOVERY_FAILED));

        // 相邻状态事件的 toStatus 必须与下一条的 fromStatus 相接，顺序与最终状态一致。
        List<LifecycleEvent> stateEvents = events.stream()
                .filter(e -> e.toStatus() != null)
                .toList();
        for (int i = 1; i < stateEvents.size(); i++) {
            assertEquals(stateEvents.get(i - 1).toStatus(),
                    stateEvents.get(i).fromStatus(),
                    "状态演进断链于第 " + i + " 条事件");
        }
        assertEquals("ACTIVE", stateEvents.get(stateEvents.size() - 1).toStatus());
    }

    @Test
    void failedVerificationIsAuditedWithoutChangingState() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("audit-2");
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(91)));
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(122)));

        var session = harness.recoveryService
                .requestRecovery("audit-2", "bad-req");
        try {
            harness.recoveryService.completeRecovery(
                    session.getSessionId(), "wrong");
        } catch (RuntimeException ignored) {
            // 预期的验证失败异常。
        }

        List<LifecycleEvent> events = harness.audit("audit-2");
        assertTrue(events.stream().anyMatch(
                e -> e.type() == EventType.RECOVERY_FAILED));
        long recovered = events.stream()
                .filter(e -> e.type() == EventType.RECOVERY_COMPLETED)
                .count();
        assertEquals(0, recovered);
    }

    @Test
    void queriesFilterByAccountAndTimeRange() {
        TestHarness harness = new TestHarness(tempDir);
        harness.register("a1");
        harness.register("a2");
        harness.activityService.report("a1",
                TestHarness.T0.plus(Duration.ofDays(10)), "a1-act");

        List<LifecycleEvent> a1 = harness.audit("a1");
        assertFalse(a1.stream().anyMatch(e -> "a2".equals(e.accountId())));

        Instant from = TestHarness.T0.plus(Duration.ofDays(5));
        Instant to = TestHarness.T0.plus(Duration.ofDays(15));
        List<LifecycleEvent> window =
                harness.auditQueryService.byAccountAndTime("a1", from, to);
        assertEquals(1, window.size());
        assertEquals(EventType.ACTIVITY_REPORTED, window.get(0).type());

        assertFalse(harness.auditQueryService.byAccountAndTime(
                "a1", to, null).contains(window.get(0)));
    }

    @Test
    void policyChangesAreAudited() {
        TestHarness harness = new TestHarness(tempDir);
        harness.policyService.updatePolicy("audited-group",
                Duration.ofDays(1), Duration.ofDays(2),
                Duration.ofDays(3), Duration.ofDays(4));

        List<LifecycleEvent> policyEvents = harness.auditQueryService.byAccount(
                "policy:audited-group");
        assertEquals(1, policyEvents.size());
        assertEquals(EventType.POLICY_CHANGED, policyEvents.get(0).type());
        assertTrue(policyEvents.get(0).detail().contains("PT24H"));
    }
}
