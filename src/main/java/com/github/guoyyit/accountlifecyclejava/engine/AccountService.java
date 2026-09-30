package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

import java.time.Duration;

public class AccountService {

    private final DurableLog log;
    private final LifecycleStore store;
    private final StateMachine stateMachine;
    private final PolicyService policyService;
    private final Clock clock;

    public AccountService(DurableLog log, LifecycleStore store, StateMachine stateMachine,
                          PolicyService policyService, Clock clock) {
        this.log = log;
        this.store = store;
        this.stateMachine = stateMachine;
        this.policyService = policyService;
        this.clock = clock;
    }

    public synchronized Account register(String accountId, String group, Duration credentialTtl,
                                         Duration accountTtl) {
        if (accountId == null || accountId.isBlank()) {
            throw new LifecycleException("账号 ID 不能为空");
        }
        if (store.getAccount(accountId) != null) {
            throw new LifecycleException("账号已存在: " + accountId);
        }
        String resolvedGroup = group == null || group.isBlank()
                ? PolicyService.DEFAULT_GROUP : group;
        policyService.effectivePolicy(resolvedGroup);
        if (credentialTtl != null && (credentialTtl.isZero() || credentialTtl.isNegative())) {
            throw new LifecycleException("凭据有效期必须为正数");
        }
        if (accountTtl != null && (accountTtl.isZero() || accountTtl.isNegative())) {
            throw new LifecycleException("账号有效期必须为正数");
        }
        long now = clock.nowMillis();
        Payloads.AccountRegistered payload = new Payloads.AccountRegistered(accountId,
                resolvedGroup, now, credentialTtl == null ? null : credentialTtl.toMillis(),
                accountTtl == null ? null : accountTtl.toMillis());
        log.append(EventType.ACCOUNT_REGISTERED, accountId, JsonCodec.toJson(payload));
        return store.getAccount(accountId);
    }

    public Account forceTransition(String accountId, AccountState target,
                                   TransitionReason reason, String note) {
        synchronized (lockFor(accountId)) {
            Account account = require(accountId);
            if (account.state() == target) {
                return account;
            }
            stateMachine.checkTransition(account.state(), target);
            long now = clock.nowMillis();
            appendStateChanged(accountId, account.state(), target, reason, now, note);
            return store.getAccount(accountId);
        }
    }

    void appendStateChanged(String accountId, AccountState from, AccountState to,
                            TransitionReason reason, long atMillis, String note) {
        Payloads.StateChanged payload = new Payloads.StateChanged(accountId, from, to, reason,
                atMillis, note);
        log.append(EventType.STATE_CHANGED, accountId, JsonCodec.toJson(payload));
    }

    Account require(String accountId) {
        Account account = store.getAccount(accountId);
        if (account == null) {
            throw new LifecycleException("账号不存在: " + accountId);
        }
        return account;
    }

    static Object lockFor(String accountId) {
        return ("acct:" + accountId).intern();
    }
}
