package com.github.guoyyit.accountlifecyclejava.model;

public record Account(
        String id,
        String group,
        AccountState state,
        TransitionReason stateReason,
        long stateEnteredAtMillis,
        long createdAtMillis,
        long lastActivityAtMillis,
        Long credentialExpiresAtMillis,
        Long accountExpiresAtMillis,
        long version
) {
}
