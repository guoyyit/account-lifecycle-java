package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 休眠与回收扫描。可被多个调度/手动调用并发执行：
 * 账号级条纹锁串行化每个账号的判定，全部迁移幂等。
 */
@Service
public class LifecycleScanner {

    private final AccountRepository accounts;
    private final CredentialService credentialService;
    private final PolicyService policyService;
    private final TransitionEvents transitionEvents;
    private final Clock clock;

    public LifecycleScanner(AccountRepository accounts,
                            ActivityService activityService,
                            CredentialService credentialService,
                            PolicyService policyService,
                            TransitionEvents transitionEvents,
                            Clock clock) {
        this.accounts = accounts;
        this.credentialService = credentialService;
        this.policyService = policyService;
        this.transitionEvents = transitionEvents;
        this.clock = clock;
    }

    public List<Account> scanOnce(Instant at) {
        List<Account> changed = new ArrayList<>();
        for (Account snapshot : accounts.findAll()) {
            Lock lock = accounts.lockFor(snapshot.getId());
            lock.lock();
            try {
                long before = accounts.require(snapshot.getId()).getVersion();
                Account account = evaluateAccount(snapshot.getId(), at);
                if (account.getVersion() != before) {
                    changed.add(account);
                }
            } finally {
                lock.unlock();
            }
        }
        return changed;
    }

    private Account evaluateAccount(String accountId, Instant at) {
        // 凭据约束优先：过期 -> 宽限，宽限超时 -> 回收。
        Account account = credentialService.evaluateCredentialLifecycle(accountId, at);

        GroupPolicy policy =
                policyService.effectivePolicy(account.getGroupName(), at);

        // 休眠判定：最后活动时刻严格早于 (at - 阈值) 才休眠；
        // at - threshold 这一临界时刻仍算活跃，边界确定并由测试锁定。
        if (account.getStatus() == AccountStatus.ACTIVE
                && account.getLastActivityAt()
                .isBefore(at.minus(policy.dormantThreshold()))) {
            account = transitionEvents.transition(account, AccountStatus.DORMANT,
                    TransitionReason.DORMANT_BY_INACTIVITY, at,
                    Map.of("lastActivityAt", account.getLastActivityAt().toString(),
                            "dormantThreshold", policy.dormantThreshold().toString()));
        }

        // 休眠回收：进入休眠后再经过可配置缓冲期才回收。
        if (account.getStatus() == AccountStatus.DORMANT) {
            Instant recycleAt =
                    account.getStatusEnteredAt().plus(policy.recycleBuffer());
            if (at.isAfter(recycleAt)) {
                account = transitionEvents.transition(account, AccountStatus.RECYCLED,
                        TransitionReason.DORMANCY_RECYCLED, at,
                        Map.of("dormantSince", account.getStatusEnteredAt().toString(),
                                "recycleBuffer", policy.recycleBuffer().toString()));
            }
        }
        return account;
    }
}
