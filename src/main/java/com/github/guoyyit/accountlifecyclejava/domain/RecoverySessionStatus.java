package com.github.guoyyit.accountlifecyclejava.domain;

/**
 * 恢复会话状态。恢复流程的中间状态独立于账号状态持久化，
 * 失败后账号仍明确停留在 RECYCLED，不留下半完成状态。
 */
public enum RecoverySessionStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
