package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.GracePeriodExpiredException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.RenewalRejectReason;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

import java.time.Duration;

public class CredentialService {

    private final DurableLog log;
    private final LifecycleStore store;
    private final StateMachine stateMachine;
    private final PolicyService policyService;
    private final Clock clock;

    public CredentialService(DurableLog log, LifecycleStore store, StateMachine stateMachine,
                             PolicyService policyService,
                             Clock clock) {
        this.log = log;
        this.store = store;
        this.stateMachine = stateMachine;
        this.policyService = policyService;
        this.clock = clock;
    }

    public void renew(String accountId, Duration newTtl) {
        if (newTtl == null || newTtl.isZero() || newTtl.isNegative()) {
            throw new LifecycleException("新凭据有效期必须为正数");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new LifecycleException("账号 ID 不能为空");
        }
        synchronized (AccountService.lockFor(accountId)) {
            long now = clock.nowMillis();
            Account account = store.getAccount(accountId);
            if (account == null) {
                auditRejection(accountId, RenewalRejectReason.ACCOUNT_NOT_FOUND,
                        "账号不存在: " + accountId, now);
                throw new LifecycleException("账号不存在: " + accountId);
            }
            AccountState state = account.state();
            if (state != AccountState.ACTIVE && state != AccountState.GRACE
                    && state != AccountState.DORMANT) {
                auditRejection(accountId, RenewalRejectReason.STATE_NOT_ALLOWED,
                        "账号处于 " + state + " 状态，不能更新凭据", now);
                throw new InvalidTransitionException(
                        "账号处于 " + state + " 状态，不能更新凭据");
            }
            if (state == AccountState.GRACE) {
                long grace = policyService.effectivePolicy(account.group())
                        .credentialGracePeriod().toMillis();
                long deadline = account.stateEnteredAtMillis() + grace;
                if (now >= deadline) {
                    auditRejection(accountId, RenewalRejectReason.GRACE_PERIOD_EXPIRED,
                            "宽限期已于 " + deadline + " 截止，凭据更新被拒绝", now);
                    throw new GracePeriodExpiredException(
                            "宽限期已截止（截止时刻 " + deadline + "），不能更新凭据: " + accountId);
                }
            }
            long newExpiresAt = now + newTtl.toMillis();
            if (state == AccountState.GRACE) {
                stateMachine.checkTransition(AccountState.GRACE, AccountState.ACTIVE);
            }
            Payloads.CredentialRenewed renewed =
                    new Payloads.CredentialRenewed(accountId, newExpiresAt, now);
            log.append(EventType.CREDENTIAL_RENEWED, accountId, JsonCodec.toJson(renewed));
        }
    }

    private void auditRejection(String accountId, RenewalRejectReason reason, String detail,
                                long atMillis) {
        Payloads.CredentialRenewalRejected payload = new Payloads.CredentialRenewalRejected(
                accountId, reason.name(), detail, atMillis);
        log.append(EventType.CREDENTIAL_RENEWAL_REJECTED, accountId, JsonCodec.toJson(payload));
    }
}
