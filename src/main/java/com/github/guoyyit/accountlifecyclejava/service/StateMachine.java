package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.error.IllegalTransitionException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 账号状态机：集中维护合法迁移表，非法迁移一律拒绝。
 */
@Component
public class StateMachine {

    private static final Map<AccountStatus, Set<AccountStatus>> ALLOWED = Map.of(
            AccountStatus.ACTIVE, Set.of(
                    AccountStatus.DORMANT, AccountStatus.GRACE, AccountStatus.DISABLED),
            AccountStatus.DORMANT, Set.of(
                    AccountStatus.ACTIVE, AccountStatus.RECYCLED, AccountStatus.DISABLED),
            AccountStatus.GRACE, Set.of(
                    AccountStatus.ACTIVE, AccountStatus.RECYCLED, AccountStatus.DISABLED),
            AccountStatus.RECYCLED, Set.of(AccountStatus.ACTIVE),
            AccountStatus.DISABLED, Set.of());

    public boolean canTransition(AccountStatus from, AccountStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void assertTransition(AccountStatus from, AccountStatus to) {
        if (from != to && !canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }
}
