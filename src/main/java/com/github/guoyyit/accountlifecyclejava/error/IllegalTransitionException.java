package com.github.guoyyit.accountlifecyclejava.error;

import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;

/**
 * 非法状态迁移。
 */
public class IllegalTransitionException extends LifecycleException {

    public IllegalTransitionException(AccountStatus from, AccountStatus to) {
        super("非法状态迁移: " + from + " -> " + to);
    }
}
