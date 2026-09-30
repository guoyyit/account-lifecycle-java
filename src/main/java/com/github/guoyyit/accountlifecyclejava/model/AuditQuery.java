package com.github.guoyyit.accountlifecyclejava.model;

public record AuditQuery(
        String accountId,
        Long fromMillisInclusive,
        Long toMillisInclusive
) {
}
