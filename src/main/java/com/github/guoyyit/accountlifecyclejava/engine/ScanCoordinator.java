package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.Policy;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ScanCoordinator {

    private final DurableLog log;
    private final LifecycleStore store;
    private final StateMachine stateMachine;
    private final PolicyService policyService;
    private final RecoveryService recoveryService;
    private final Clock clock;
    private final ConcurrentHashMap<String, Boolean> touched = new ConcurrentHashMap<>();

    public ScanCoordinator(DurableLog log, LifecycleStore store, StateMachine stateMachine,
                           PolicyService policyService, RecoveryService recoveryService, Clock clock) {
        this.log = log;
        this.store = store;
        this.stateMachine = stateMachine;
        this.policyService = policyService;
        this.recoveryService = recoveryService;
        this.clock = clock;
    }

    public int scanOnce() {
        int transitions = 0;
        for (Account account : store.allAccounts()) {
            if (scanAccount(account.id())) {
                transitions++;
            }
        }
        return transitions;
    }

    public boolean scanAccount(String accountId) {
        synchronized (AccountService.lockFor(accountId)) {
            touched.put(accountId, Boolean.TRUE);
            Account account = store.getAccount(accountId);
            if (account == null) {
                return false;
            }
            expireStaleRecovery(account);
            account = store.getAccount(accountId);
            if (account.state() == AccountState.RECYCLED
                    || account.state() == AccountState.DISABLED) {
                return false;
            }
            Policy policy = policyService.effectivePolicy(account.group());
            long now = clock.nowMillis();
            if (expireAccountIfDue(account, now)) {
                return true;
            }
            account = store.getAccount(accountId);
            if (expireCredentialIfDue(account, now)) {
                return true;
            }
            account = store.getAccount(accountId);
            if (recycleAfterGraceTimeout(account, now)) {
                return true;
            }
            account = store.getAccount(accountId);
            if (markDormantIfIdle(account, policy, now)) {
                return true;
            }
            account = store.getAccount(accountId);
            return recycleDormantAfterBuffer(account, policy, now);
        }
    }

    public List<String> touchedAccounts() {
        return List.copyOf(touched.keySet());
    }

    private void expireStaleRecovery(Account account) {
        PendingRecovery recovery = store.findRecoveryByAccount(account.id());
        if (recovery == null) {
            return;
        }
        long ttl = policyService.effectivePolicy(account.group()).recoveryPendingTtl().toMillis();
        long now = clock.nowMillis();
        if (now - recovery.createdAtMillis() >= ttl) {
            Payloads.RecoveryFailed payload = new Payloads.RecoveryFailed(recovery.requestId(),
                    account.id(), RecoveryStatus.FAILED, "恢复流程超过 TTL 未完成", now);
            log.append(EventType.RECOVERY_FAILED, account.id(), JsonCodec.toJson(payload));
        }
    }

    private boolean expireAccountIfDue(Account account, long now) {
        if (account.accountExpiresAtMillis() == null
                || now < account.accountExpiresAtMillis()) {
            return false;
        }
        if (account.state() == AccountState.DORMANT) {
            transition(account, AccountState.DISABLED, TransitionReason.ACCOUNT_EXPIRED, now,
                    "账号到达整体有效期，休眠中直接禁用");
        } else {
            transition(account, AccountState.DISABLED, TransitionReason.ACCOUNT_EXPIRED, now,
                    "账号到达整体有效期");
        }
        return true;
    }

    private boolean expireCredentialIfDue(Account account, long now) {
        if (account.credentialExpiresAtMillis() == null
                || now < account.credentialExpiresAtMillis()
                || account.state() == AccountState.GRACE) {
            return false;
        }
        transition(account, AccountState.GRACE, TransitionReason.CREDENTIAL_EXPIRED, now,
                "凭据超过有效期，进入宽限期");
        return true;
    }

    private boolean recycleAfterGraceTimeout(Account account, long now) {
        if (account.state() != AccountState.GRACE) {
            return false;
        }
        long grace = policyService.effectivePolicy(account.group())
                .credentialGracePeriod().toMillis();
        if (now - account.stateEnteredAtMillis() < grace) {
            return false;
        }
        transition(account, AccountState.RECYCLED, TransitionReason.GRACE_TIMEOUT_RECYCLE, now,
                "宽限期内未更新凭据，账号被回收");
        return true;
    }

    private boolean markDormantIfIdle(Account account, Policy policy, long now) {
        if (account.state() != AccountState.ACTIVE) {
            return false;
        }
        long threshold = policy.dormancyThreshold().toMillis();
        if (now - account.lastActivityAtMillis() < threshold) {
            return false;
        }
        transition(account, AccountState.DORMANT, TransitionReason.DORMANCY_SCAN, now,
                "连续 " + threshold + "ms 无活动，判定休眠");
        return true;
    }

    private boolean recycleDormantAfterBuffer(Account account, Policy policy, long now) {
        if (account.state() != AccountState.DORMANT) {
            return false;
        }
        long buffer = policy.dormantBufferPeriod().toMillis();
        if (now - account.stateEnteredAtMillis() < buffer) {
            return false;
        }
        transition(account, AccountState.RECYCLED, TransitionReason.DORMANT_BUFFER_RECYCLE, now,
                "休眠缓冲期结束仍无活动，账号被回收");
        return true;
    }

    private void transition(Account account, AccountState target, TransitionReason reason, long now,
                            String note) {
        if (account.state() == target) {
            return;
        }
        stateMachine.checkTransition(account.state(), target);
        Payloads.StateChanged payload = new Payloads.StateChanged(account.id(), account.state(),
                target, reason, now, note);
        log.append(EventType.STATE_CHANGED, account.id(), JsonCodec.toJson(payload));
    }
}
