package com.github.guoyyit.accountlifecyclejava.model;

public record PendingRecovery(
        String requestId,
        String accountId,
        RecoveryStatus status,
        long createdAtMillis,
        Long verifiedAtMillis,
        String failReason
) {
}
