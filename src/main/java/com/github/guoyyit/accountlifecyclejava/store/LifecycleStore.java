package com.github.guoyyit.accountlifecyclejava.store;

import com.github.guoyyit.accountlifecyclejava.event.Event;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.Policy;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LifecycleStore {

    private final Map<String, AccountHolder> accounts = new LinkedHashMap<>();
    private final Map<String, List<Policy>> policies = new LinkedHashMap<>();
    private final Map<String, PendingRecovery> recoveries = new LinkedHashMap<>();

    private static final class AccountHolder {
        private String id;
        private String group;
        private AccountState state;
        private TransitionReason stateReason;
        private long stateEnteredAt;
        private long createdAt;
        private long lastActivityAt;
        private Long credentialExpiresAt;
        private Long accountExpiresAt;
        private long version;

        private Account toRecord() {
            return new Account(id, group, state, stateReason, stateEnteredAt, createdAt,
                    lastActivityAt, credentialExpiresAt, accountExpiresAt, version);
        }
    }

    public void reset() {
        accounts.clear();
        policies.clear();
        recoveries.clear();
    }

    public void apply(Event event) {
        switch (event.type()) {
            case ACCOUNT_REGISTERED -> applyRegistered(event);
            case ACTIVITY_REPORTED -> applyActivity(event);
            case STATE_CHANGED -> applyStateChanged(event);
            case CREDENTIAL_UPDATED -> applyCredentialUpdated(event);
            case POLICY_PUT -> applyPolicyPut(event);
            case RECOVERY_STARTED -> applyRecoveryStarted(event);
            case RECOVERY_VERIFIED -> applyRecoveryVerified(event);
            case RECOVERY_COMPLETED -> applyRecoveryCompleted(event);
            case RECOVERY_FAILED -> applyRecoveryFailed(event);
        }
    }

    public Account getAccount(String accountId) {
        AccountHolder holder = accounts.get(accountId);
        return holder == null ? null : holder.toRecord();
    }

    public Collection<Account> allAccounts() {
        return accounts.values().stream().map(AccountHolder::toRecord).toList();
    }

    public Policy getPolicy(String group) {
        List<Policy> history = policies.get(group);
        if (history == null || history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1);
    }

    public List<Policy> policyHistory(String group) {
        return List.copyOf(policies.getOrDefault(group, List.of()));
    }

    public PendingRecovery getRecovery(String requestId) {
        return recoveries.get(requestId);
    }

    public PendingRecovery findRecoveryByAccount(String accountId) {
        return recoveries.values().stream()
                .filter(r -> r.accountId().equals(accountId))
                .filter(r -> r.status() == RecoveryStatus.PENDING
                        || r.status() == RecoveryStatus.VERIFIED)
                .max(Comparator.comparingLong(PendingRecovery::createdAtMillis))
                .orElse(null);
    }

    private AccountHolder requireAccount(String accountId) {
        AccountHolder holder = accounts.get(accountId);
        if (holder == null) {
            throw new LifecycleException("账号不存在: " + accountId);
        }
        return holder;
    }

    private void applyRegistered(Event event) {
        Payloads.AccountRegistered p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.AccountRegistered.class);
        AccountHolder holder = new AccountHolder();
        holder.id = p.accountId();
        holder.group = p.group();
        holder.state = AccountState.ACTIVE;
        holder.stateReason = TransitionReason.CREATED;
        holder.stateEnteredAt = p.nowMillis();
        holder.createdAt = p.nowMillis();
        holder.lastActivityAt = p.nowMillis();
        holder.credentialExpiresAt = p.credentialTtlMillis() == null
                ? null : p.nowMillis() + p.credentialTtlMillis();
        holder.accountExpiresAt = p.accountTtlMillis() == null
                ? null : p.nowMillis() + p.accountTtlMillis();
        holder.version = event.sequence();
        accounts.put(p.accountId(), holder);
    }

    private void applyActivity(Event event) {
        Payloads.ActivityReported p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.ActivityReported.class);
        AccountHolder holder = requireAccount(p.accountId());
        holder.lastActivityAt = Math.max(holder.lastActivityAt, p.activityAtMillis());
        holder.version = event.sequence();
    }

    private void applyStateChanged(Event event) {
        Payloads.StateChanged p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.StateChanged.class);
        AccountHolder holder = requireAccount(p.accountId());
        holder.state = p.to();
        holder.stateReason = p.reason();
        holder.stateEnteredAt = p.atMillis();
        holder.version = event.sequence();
    }

    private void applyCredentialUpdated(Event event) {
        Payloads.CredentialUpdated p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.CredentialUpdated.class);
        AccountHolder holder = requireAccount(p.accountId());
        holder.credentialExpiresAt = p.newExpiresAtMillis();
        holder.version = event.sequence();
    }

    private void applyPolicyPut(Event event) {
        Payloads.PolicyPut p = JsonCodec.fromJson(event.payloadJson(), Payloads.PolicyPut.class);
        policies.computeIfAbsent(p.group(), g -> new ArrayList<>())
                .add(JsonCodec.fromJson(p.policyJson(), Policy.class));
    }

    private void applyRecoveryStarted(Event event) {
        Payloads.RecoveryStarted p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.RecoveryStarted.class);
        recoveries.put(p.requestId(), new PendingRecovery(p.requestId(), p.accountId(),
                RecoveryStatus.PENDING, p.atMillis(), null, null));
    }

    private void applyRecoveryVerified(Event event) {
        Payloads.RecoveryVerified p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.RecoveryVerified.class);
        PendingRecovery base = requireRecovery(p.requestId());
        recoveries.put(p.requestId(), new PendingRecovery(base.requestId(), base.accountId(),
                RecoveryStatus.VERIFIED, base.createdAtMillis(), p.atMillis(), base.failReason()));
    }

    private void applyRecoveryCompleted(Event event) {
        Payloads.RecoveryCompleted p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.RecoveryCompleted.class);
        PendingRecovery base = requireRecovery(p.requestId());
        Long verifiedAt = base.verifiedAtMillis() == null ? p.atMillis() : base.verifiedAtMillis();
        recoveries.put(p.requestId(), new PendingRecovery(base.requestId(), base.accountId(),
                RecoveryStatus.COMPLETED, base.createdAtMillis(), verifiedAt, base.failReason()));
        AccountHolder holder = requireAccount(p.accountId());
        holder.state = p.restoredState();
        holder.stateReason = TransitionReason.RECOVERY_VERIFIED;
        holder.stateEnteredAt = p.atMillis();
        holder.version = event.sequence();
    }

    private void applyRecoveryFailed(Event event) {
        Payloads.RecoveryFailed p =
                JsonCodec.fromJson(event.payloadJson(), Payloads.RecoveryFailed.class);
        PendingRecovery base = recoveries.get(p.requestId());
        PendingRecovery merged = base != null ? base
                : new PendingRecovery(p.requestId(), p.accountId(), RecoveryStatus.PENDING,
                p.atMillis(), null, null);
        recoveries.put(p.requestId(), new PendingRecovery(merged.requestId(), merged.accountId(),
                RecoveryStatus.FAILED, merged.createdAtMillis(), merged.verifiedAtMillis(),
                p.failReason()));
    }

    private PendingRecovery requireRecovery(String requestId) {
        PendingRecovery existing = recoveries.get(requestId);
        if (existing == null) {
            throw new LifecycleException("恢复记录不存在: " + requestId);
        }
        return existing;
    }
}
