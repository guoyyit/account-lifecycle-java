package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

import java.time.Duration;

public class CredentialService {

    private final DurableLog log;
    private final LifecycleStore store;
    private final StateMachine stateMachine;
    private final Clock clock;

    public CredentialService(DurableLog log, LifecycleStore store, StateMachine stateMachine,
                             Clock clock) {
        this.log = log;
        this.store = store;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public void renew(String accountId, Duration newTtl) {
        if (newTtl == null || newTtl.isZero() || newTtl.isNegative()) {
            throw new LifecycleException("新凭据有效期必须为正数");
        }
        synchronized (AccountService.lockFor(accountId)) {
            Account account = store.getAccount(accountId);
            if (account == null) {
                throw new LifecycleException("账号不存在: " + accountId);
            }
            AccountState state = account.state();
            if (state != AccountState.ACTIVE && state != AccountState.GRACE
                    && state != AccountState.DORMANT) {
                throw new InvalidTransitionException(
                        "账号处于 " + state + " 状态，不能更新凭据");
            }
            long now = clock.nowMillis();
            long newExpiresAt = now + newTtl.toMillis();
            Payloads.CredentialUpdated credentialUpdated =
                    new Payloads.CredentialUpdated(accountId, newExpiresAt, now);
            log.append(EventType.CREDENTIAL_UPDATED, accountId,
                    JsonCodec.toJson(credentialUpdated));
            if (state == AccountState.GRACE) {
                stateMachine.checkTransition(AccountState.GRACE, AccountState.ACTIVE);
                Payloads.StateChanged stateChanged = new Payloads.StateChanged(accountId,
                        AccountState.GRACE, AccountState.ACTIVE, TransitionReason.CREDENTIAL_RENEWED,
                        now, "宽限期内完成凭据更新，恢复正常");
                log.append(EventType.STATE_CHANGED, accountId, JsonCodec.toJson(stateChanged));
            }
        }
    }
}
