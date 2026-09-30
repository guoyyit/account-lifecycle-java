package com.github.guoyyit.accountlifecyclejava.domain;

import java.time.Instant;

/**
 * 账号聚合。当前状态、进入原因与进入时间一起构成可解释的快照。
 */
public class Account {

    private String id;
    private String groupName;
    private Instant createdAt;
    private Instant lastActivityAt;
    private Instant credentialExpiresAt;
    private AccountStatus status;
    private TransitionReason statusReason;
    private Instant statusEnteredAt;
    private long version;
    private String lastTransitionEventId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Instant lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public Instant getCredentialExpiresAt() {
        return credentialExpiresAt;
    }

    public void setCredentialExpiresAt(Instant credentialExpiresAt) {
        this.credentialExpiresAt = credentialExpiresAt;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public TransitionReason getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(TransitionReason statusReason) {
        this.statusReason = statusReason;
    }

    public Instant getStatusEnteredAt() {
        return statusEnteredAt;
    }

    public void setStatusEnteredAt(Instant statusEnteredAt) {
        this.statusEnteredAt = statusEnteredAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getLastTransitionEventId() {
        return lastTransitionEventId;
    }

    public void setLastTransitionEventId(String lastTransitionEventId) {
        this.lastTransitionEventId = lastTransitionEventId;
    }
}
