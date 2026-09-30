package com.github.guoyyit.accountlifecyclejava.event;

import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;

public final class Payloads {
    private Payloads() {
    }

    public record AccountRegistered(String accountId, String group, long nowMillis,
                                    Long credentialTtlMillis, Long accountTtlMillis) {
    }

    public record ActivityReported(String accountId, long activityAtMillis) {
    }

    public record StateChanged(String accountId, AccountState from, AccountState to,
                               TransitionReason reason, long atMillis, String note) {
    }

    public record CredentialUpdated(String accountId, long newExpiresAtMillis, long atMillis) {
    }

    public record PolicyPut(String group, int revision, String policyJson, long atMillis, String reason) {
    }

    public record RecoveryStarted(String requestId, String accountId, long ttlMillis, long atMillis) {
    }

    public record RecoveryVerified(String requestId, String accountId, long atMillis) {
    }

    public record RecoveryCompleted(String requestId, String accountId, AccountState restoredState,
                                    RecoveryStatus status, long atMillis) {
    }

    public record RecoveryFailed(String requestId, String accountId, RecoveryStatus status,
                                 String failReason, long atMillis) {
    }
}
