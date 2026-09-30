package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

public class ActivityService {

    private final DurableLog log;
    private final LifecycleStore store;
    private final StateMachine stateMachine;
    private final Clock clock;

    public ActivityService(DurableLog log, LifecycleStore store, StateMachine stateMachine, Clock clock) {
        this.log = log;
        this.store = store;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public void report(String accountId) {
        report(accountId, clock.nowMillis());
    }

    public void report(String accountId, long activityAtMillis) {
        synchronized (AccountService.lockFor(accountId)) {
            Account account = store.getAccount(accountId);
            if (account == null) {
                throw new com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException(
                        "账号不存在: " + accountId);
            }
            if (account.state() == AccountState.RECYCLED
                    || account.state() == AccountState.DISABLED) {
                throw new com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException(
                        "账号处于 " + account.state() + " 状态，不能上报活动: " + accountId);
            }
            long effectiveAt = Math.max(activityAtMillis, clock.nowMillis());
            Payloads.ActivityReported payload =
                    new Payloads.ActivityReported(accountId, effectiveAt);
            log.append(EventType.ACTIVITY_REPORTED, accountId, JsonCodec.toJson(payload));
            Account refreshed = store.getAccount(accountId);
            if (refreshed.state() == AccountState.DORMANT) {
                stateMachine.checkTransition(AccountState.DORMANT, AccountState.ACTIVE);
                Payloads.StateChanged stateChanged = new Payloads.StateChanged(accountId,
                        AccountState.DORMANT, AccountState.ACTIVE, TransitionReason.ACTIVITY_REPORTED,
                        effectiveAt, "休眠期间收到活动信号，恢复正常");
                log.append(EventType.STATE_CHANGED, accountId, JsonCodec.toJson(stateChanged));
            }
        }
    }
}
