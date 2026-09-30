package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Policy;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class PolicyService {

    public static final String DEFAULT_GROUP = "default";

    public record Defaults(Duration dormancyThreshold, Duration credentialGracePeriod,
                           Duration dormantBufferPeriod, Duration accountMaxLifetime,
                           Duration recoveryPendingTtl) {
    }

    private final DurableLog log;
    private final LifecycleStore store;
    private final Clock clock;
    private final Defaults defaults;

    public PolicyService(DurableLog log, LifecycleStore store, Clock clock, Defaults defaults) {
        this.log = log;
        this.store = store;
        this.clock = clock;
        this.defaults = Objects.requireNonNull(defaults, "默认策略不能为空");
    }

    public synchronized void seedDefaults() {
        if (store.getPolicy(DEFAULT_GROUP) == null) {
            appendPolicy(DEFAULT_GROUP, defaults.dormancyThreshold(), defaults.credentialGracePeriod(),
                    defaults.dormantBufferPeriod(), defaults.accountMaxLifetime(),
                    defaults.recoveryPendingTtl(), 1, "系统初始化默认策略");
        }
    }

    public synchronized Policy putPolicy(String group, Duration dormancyThreshold,
                                         Duration credentialGracePeriod, Duration dormantBufferPeriod,
                                         Duration accountMaxLifetime, Duration recoveryPendingTtl,
                                         String reason) {
        String normalized = group == null || group.isBlank() ? DEFAULT_GROUP : group;
        validatePositive(dormancyThreshold, "dormancyThreshold");
        validatePositive(credentialGracePeriod, "credentialGracePeriod");
        validatePositive(dormantBufferPeriod, "dormantBufferPeriod");
        validatePositive(recoveryPendingTtl, "recoveryPendingTtl");
        Policy current = store.getPolicy(normalized);
        int revision = current == null ? 1 : current.revision() + 1;
        return appendPolicy(normalized, dormancyThreshold, credentialGracePeriod, dormantBufferPeriod,
                accountMaxLifetime == null ? Duration.ZERO : accountMaxLifetime, recoveryPendingTtl,
                revision, reason == null ? "策略变更" : reason);
    }

    public synchronized Policy effectivePolicy(String group) {
        Policy exact = store.getPolicy(group);
        if (exact != null) {
            return exact;
        }
        Policy fallback = store.getPolicy(DEFAULT_GROUP);
        if (fallback != null) {
            return fallback;
        }
        throw new LifecycleException("默认策略尚未初始化");
    }

    public synchronized List<Policy> history(String group) {
        List<Policy> groupHistory = store.policyHistory(group);
        if (!groupHistory.isEmpty()) {
            return groupHistory;
        }
        if (!DEFAULT_GROUP.equals(group)) {
            return store.policyHistory(DEFAULT_GROUP);
        }
        return List.of();
    }

    private void validatePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new LifecycleException("策略项 " + name + " 必须为正数");
        }
    }

    private Policy appendPolicy(String group, Duration dormancyThreshold, Duration credentialGracePeriod,
                                Duration dormantBufferPeriod, Duration accountMaxLifetime,
                                Duration recoveryPendingTtl, int revision, String reason) {
        long now = clock.nowMillis();
        Policy policy = new Policy(group, revision, dormancyThreshold, credentialGracePeriod,
                dormantBufferPeriod, accountMaxLifetime, recoveryPendingTtl, now, reason);
        Payloads.PolicyPut payload = new Payloads.PolicyPut(group, revision, JsonCodec.toJson(policy),
                now, reason);
        log.append(EventType.POLICY_PUT, null, JsonCodec.toJson(payload));
        return policy;
    }
}
