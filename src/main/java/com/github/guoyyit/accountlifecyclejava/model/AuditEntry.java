package com.github.guoyyit.accountlifecyclejava.model;

public record AuditEntry(
        long sequence,
        long timestampMillis,
        String accountId,
        String eventType,
        String detail
) {
}
