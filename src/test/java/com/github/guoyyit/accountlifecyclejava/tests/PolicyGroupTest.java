package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.Policy;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyGroupTest {

    @TempDir
    Path tempDir;

    @Test
    void groupsUseOwnThresholdsAndChangesAreNotRetroactive() {
        MutableClock clock = MutableClock.startAt(60_000L);
        LifecycleHarness harness = new LifecycleHarness(tempDir, clock,
                LifecycleHarness.defaults(Duration.ofMillis(100), Duration.ofDays(1),
                        Duration.ofDays(10), Duration.ofHours(1)));

        harness.policyService.putPolicy("vip", Duration.ofMillis(500), Duration.ofDays(2),
                Duration.ofDays(20), Duration.ZERO, Duration.ofHours(2), "VIP 分组更宽松");

        harness.accountService.register("normal", "default", null, null);
        harness.accountService.register("vip1", "vip", null, null);

        clock.advanceMillis(120);
        harness.scanCoordinator.scanOnce();

        assertEquals(AccountState.DORMANT, harness.store.getAccount("normal").state());
        assertEquals(AccountState.ACTIVE, harness.store.getAccount("vip1").state());

        TransitionReason historicalReason = harness.store.getAccount("normal").stateReason();

        harness.policyService.putPolicy("default", Duration.ofMillis(10_000), Duration.ofDays(1),
                Duration.ofDays(10), Duration.ZERO, Duration.ofHours(1), "默认阈值收紧");

        Account normal = harness.store.getAccount("normal");
        assertEquals(AccountState.DORMANT, normal.state());
        assertSame(TransitionReason.DORMANCY_SCAN, historicalReason);
        assertEquals(2, harness.policyService.history("default").size());

        Policy current = harness.policyService.effectivePolicy("default");
        assertEquals(2, current.revision());
        assertEquals(Duration.ofMillis(10_000), current.dormancyThreshold());

        Policy unknown = harness.policyService.effectivePolicy("unknown-group");
        assertEquals("default", unknown.group());

        assertTrue(harness.policyService.history("default").get(0).createdAtMillis()
                <= harness.policyService.history("default").get(1).createdAtMillis());
    }
}
