package com.github.guoyyit.accountlifecyclejava.event;

/**
 * 生命周期事件类型。所有判定结果只以追加事件的方式落盘，支持重放。
 */
public enum EventType {
    ACCOUNT_REGISTERED,
    ACTIVITY_REPORTED,
    STATE_TRANSITION,
    CREDENTIAL_ISSUED,
    RECOVERY_REQUESTED,
    RECOVERY_COMPLETED,
    RECOVERY_FAILED,
    POLICY_CHANGED
}
