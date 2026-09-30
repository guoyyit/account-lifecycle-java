package com.github.guoyyit.accountlifecyclejava.model;

import java.time.Duration;

public record Policy(
        String group,
        int revision,
        Duration dormancyThreshold,
        Duration credentialGracePeriod,
        Duration dormantBufferPeriod,
        Duration accountMaxLifetime,
        Duration recoveryPendingTtl,
        long createdAtMillis,
        String reason
) {
}
