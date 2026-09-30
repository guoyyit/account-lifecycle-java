package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.service.PolicyService;
import com.github.guoyyit.accountlifecyclejava.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyGroupTest {

    @TempDir
    Path tempDir;

    @Test
    void groupsUseDifferentDormantThresholds() {
        TestHarness harness = new TestHarness(tempDir);
        harness.policyService.updatePolicy("sensitive",
                Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(2), Duration.ofDays(3));

        harness.accountService.register("s1", "sensitive", null);
        harness.accountService.register("n1", PolicyService.DEFAULT_GROUP, null);

        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(8)));

        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("s1").getStatus());
        assertEquals(AccountStatus.ACTIVE,
                harness.accountService.get("n1").getStatus());
    }

    @Test
    void unknownGroupFallsBackToDefault() {
        TestHarness harness = new TestHarness(tempDir);
        Account account = harness.accountService.register("u1", "not-defined", null);
        assertEquals(PolicyService.DEFAULT_GROUP, account.getGroupName());
    }

    @Test
    void policyChangeOnlyAffectsLaterDecisions() {
        TestHarness harness = new TestHarness(tempDir);
        harness.accountService.register("p1", PolicyService.DEFAULT_GROUP, null);

        // 第 80 天：旧策略 90 天阈值，账号仍活跃。
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(80)));
        assertEquals(AccountStatus.ACTIVE,
                harness.accountService.get("p1").getStatus());

        // 第 81 天收紧阈值到 70 天，此前没有被追溯判休眠。
        harness.setTime(TestHarness.T0.plus(Duration.ofDays(81)));
        GroupPolicy v2 = harness.policyService.updatePolicy(
                PolicyService.DEFAULT_GROUP,
                Duration.ofDays(70), Duration.ofDays(365),
                Duration.ofDays(7), Duration.ofDays(30));
        assertEquals(2, v2.version());

        // 重放历史版本：第 80 天仍按 v1(90d) 判定，审计中当时状态保持 ACTIVE。
        GroupPolicy atDay80 = harness.policyService.effectivePolicy(
                PolicyService.DEFAULT_GROUP,
                TestHarness.T0.plus(Duration.ofDays(80)));
        assertEquals(Duration.ofDays(90), atDay80.dormantThreshold());

        // 变更之后立即按新阈值判定休眠（已 81 天无活动 > 70 天）。
        harness.scanner.scanOnce(TestHarness.T0.plus(Duration.ofDays(81)));
        assertEquals(AccountStatus.DORMANT,
                harness.accountService.get("p1").getStatus());

        assertEquals(2, harness.policyService.versions(
                PolicyService.DEFAULT_GROUP).size());
    }
}
