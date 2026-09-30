package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.error.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 活动上报。上报与扫描共享同一把账号锁；
 * 只有“活动时间严格早于休眠阈值时刻”才会判休眠，临界时刻确定。
 */
@Service
public class ActivityService {

    private final AccountRepository accounts;
    private final PolicyService policyService;
    private final TransitionEvents transitionEvents;
    private final Clock clock;

    public ActivityService(AccountRepository accounts,
                           IdempotentEvents idempotentEvents,
                           PolicyService policyService,
                           TransitionEvents transitionEvents,
                           Clock clock) {
        this.accounts = accounts;
        this.policyService = policyService;
        this.transitionEvents = transitionEvents;
        this.clock = clock;
    }

    public Account report(String accountId, Instant activityAt, String idempotencyKey) {
        Instant at = activityAt == null ? clock.instant() : activityAt;
        Lock lock = accounts.lockFor(accountId);
        lock.lock();
        try {
            Account account = accounts.require(accountId);
            if (account.getStatus() == AccountStatus.DISABLED) {
                throw new LifecycleException("账号已禁用，拒绝活动上报: " + accountId);
            }
            if (account.getStatus() == AccountStatus.RECYCLED) {
                throw new LifecycleException("账号已回收，活动上报无效，需走恢复流程: " + accountId);
            }
            transitionEvents.recordActivity(accountId, at, idempotencyKey);
            Account refreshed = accounts.require(accountId);
            // 上报与扫描共用同一把账号锁：若扫描已在本锁前判其休眠，
            // 这里根据同一阈值原子地把它判回 ACTIVE；反过来休眠扫描在锁内看到本事件后也不会误判。
            if (refreshed.getStatus() == AccountStatus.DORMANT) {
                return transitionEvents.transition(refreshed, AccountStatus.ACTIVE,
                        TransitionReason.REACTIVATED_BY_ACTIVITY, at,
                        Map.of("lastActivityAt", at.toString()));
            }
            return refreshed;
        } finally {
            lock.unlock();
        }
    }
}
