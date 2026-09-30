package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.error.IllegalTransitionException;
import com.github.guoyyit.accountlifecyclejava.error.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 凭据过期与宽限期约束。凭据到期的临界时刻采用 isAfter 判定：
 * now == expiresAt 仍然有效，超过后才进入 GRACE。
 */
@Service
public class CredentialService {

    private final AccountRepository accounts;
    private final IdempotentEvents idempotentEvents;
    private final PolicyService policyService;
    private final TransitionEvents transitionEvents;
    private final Clock clock;

    public CredentialService(AccountRepository accounts,
                             IdempotentEvents idempotentEvents,
                             PolicyService policyService,
                             TransitionEvents transitionEvents,
                             Clock clock) {
        this.accounts = accounts;
        this.idempotentEvents = idempotentEvents;
        this.policyService = policyService;
        this.transitionEvents = transitionEvents;
        this.clock = clock;
    }

    public Account evaluateCredentialLifecycle(String accountId, Instant at) {
        Lock lock = accounts.lockFor(accountId);
        lock.lock();
        try {
            Account account = accounts.require(accountId);
            if (account.getStatus() == AccountStatus.ACTIVE && isExpired(account, at)) {
                // 临界语义：at == expiresAt 时凭据仍有效；仅当 at 严格晚于到期时刻才过期。
                return transitionEvents.transition(account, AccountStatus.GRACE,
                        TransitionReason.CREDENTIAL_EXPIRED, at,
                        Map.of("credentialExpiresAt",
                                account.getCredentialExpiresAt().toString()));
            }
            if (account.getStatus() == AccountStatus.GRACE && graceTimedOut(account, at)) {
                return transitionEvents.transition(account, AccountStatus.RECYCLED,
                        TransitionReason.GRACE_TIMEOUT_RECYCLED, at,
                        Map.of("graceEnteredAt", account.getStatusEnteredAt().toString()));
            }
            return account;
        } finally {
            lock.unlock();
        }
    }

    public Account renewCredential(String accountId, String idempotencyKey) {
        Instant now = clock.instant();
        Lock lock = accounts.lockFor(accountId);
        lock.lock();
        try {
            Account account = accounts.require(accountId);
            if (idempotencyKey != null
                    && idempotentEvents.wasProcessed(idempotencyKey)) {
                // 同一更新请求重试：首次结果已经落盘，直接返回当前状态，不重复迁移。
                return account;
            }
            if (account.getStatus() != AccountStatus.GRACE) {
                throw new IllegalTransitionException(account.getStatus(), AccountStatus.ACTIVE);
            }
            if (graceTimedOut(account, now)) {
                // 宽限期已到临界之外：先回收，拒绝更新，避免临界时刻行为不确定。
                account = transitionEvents.transition(account, AccountStatus.RECYCLED,
                        TransitionReason.GRACE_TIMEOUT_RECYCLED, now,
                        Map.of("graceEnteredAt", account.getStatusEnteredAt().toString()));
                throw new LifecycleException("宽限期已结束，账号已回收，需走恢复流程");
            }
            GroupPolicy policy =
                    policyService.effectivePolicy(account.getGroupName(), now);
            Instant expiresAt = now.plus(policy.credentialValidity());
            transitionEvents.issueCredential(accountId, expiresAt, now, idempotencyKey);
            Account refreshed = accounts.require(accountId);
            return transitionEvents.transition(refreshed, AccountStatus.ACTIVE,
                    TransitionReason.CREDENTIAL_RENEWED, now,
                    Map.of("credentialExpiresAt", expiresAt.toString()));
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpired(Account account, Instant at) {
        return at.isAfter(account.getCredentialExpiresAt());
    }

    private boolean graceTimedOut(Account account, Instant at) {
        GroupPolicy policy =
                policyService.effectivePolicy(account.getGroupName(), at);
        Instant deadline = account.getStatusEnteredAt().plus(policy.gracePeriod());
        // 临界语义：at == deadline 仍在宽限期内；严格晚于截止时刻才算超时。
        return at.isAfter(deadline);
    }
}
