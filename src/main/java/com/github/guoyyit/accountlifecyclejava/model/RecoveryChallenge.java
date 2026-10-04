package com.github.guoyyit.accountlifecyclejava.model;

public record RecoveryChallenge(
        PendingRecovery recovery,
        String verificationToken,
        long tokenExpiresAtMillis
) {
}
