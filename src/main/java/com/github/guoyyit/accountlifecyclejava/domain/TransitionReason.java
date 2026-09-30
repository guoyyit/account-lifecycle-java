package com.github.guoyyit.accountlifecyclejava.domain;

/**
 * 进入某个状态的原因，保证任一时刻状态可解释。
 */
public enum TransitionReason {
    REGISTERED,
    DORMANT_BY_INACTIVITY,
    REACTIVATED_BY_ACTIVITY,
    CREDENTIAL_EXPIRED,
    CREDENTIAL_RENEWED,
    GRACE_TIMEOUT_RECYCLED,
    DORMANCY_RECYCLED,
    DISABLED,
    RECOVERED
}
