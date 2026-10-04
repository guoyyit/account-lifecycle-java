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
    private final PolicyService policyService;
    private final Clock clock;

    public CredentialService(DurableLog log, LifecycleStore store, PolicyService policyService,
                             Clock clock) {
        this.log = log;
        this.store = store;
        this.policyService = policyService;
        this.clock = clock;
    }

    public void renew(String accountId, Duration newTtl) {
        if (newTtl == null || newTtl.isZero() || newTtl.isNegative()) {
            throw new LifecycleException("新凭据有效期必须为正数");
        }
        long submittedAt = clock.nowMillis();
        synchronized (AccountService.lockFor(accountId)) {
            Account account = store.getAccount(accountId);
            if (account == null) {
                reject(accountId, RenewalRejectReason.ACCOUNT_NOT_FOUND, submittedAt);
                throw new LifecycleException("账号不存在: " + accountId);
            }
            AccountState state = account.state();
            if (state != AccountState.ACTIVE && state != AccountState.GRACE
                    && state != AccountState.DORMANT) {
                reject(accountId, RenewalRejectReason.STATE_NOT_ALLOWED, submittedAt);
                throw new InvalidTransitionException(
                        "账号处于 " + state + " 状态，不能更新凭据");
            }
            long newExpiresAt = submittedAt + newTtl.toMillis();
            if (state == AccountState.GRACE) {
                long graceMillis = policyService.effectivePolicy(account.group())
                        .credentialGracePeriod().toMillis();
                long deadline = account.stateEnteredAtMillis() + graceMillis;
                if (submittedAt >= deadline) {
                    reject(accountId, RenewalRejectReason.GRACE_PERIOD_EXPIRED, submittedAt);
                    throw new GracePeriodExpiredException(
                            "宽限期已于 " + deadline + " 截止，凭据更新被拒绝: " + accountId);
                }
                Payloads.CredentialRenewed renewed =
                        new Payloads.CredentialRenewed(accountId, newExpiresAt, submittedAt);
                log.append(EventType.CREDENTIAL_RENEWED, accountId, JsonCodec.toJson(renewed));
            } else {
                Payloads.CredentialUpdated credentialUpdated =
                        new Payloads.CredentialUpdated(accountId, newExpiresAt, submittedAt);
                log.append(EventType.CREDENTIAL_UPDATED, accountId,
                        JsonCodec.toJson(credentialUpdated));
            }
        }
    }

    private void reject(String accountId, RenewalRejectReason reason, long atMillis) {
        Payloads.CredentialRenewalRejected payload =
                new Payloads.CredentialRenewalRejected(accountId, reason, atMillis);
        log.append(EventType.CREDENTIAL_RENEWAL_REJECTED, accountId, JsonCodec.toJson(payload));
    }
}
