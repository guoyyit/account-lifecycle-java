package com.github.guoyyit.accountlifecyclejava.domain;

import java.time.Instant;

/**
 * 账号恢复会话。完整生命周期事件化持久化，重复请求按幂等键去重。
 */
public class RecoverySession {

    private String sessionId;
    private String accountId;
    private String idempotencyKey;
    private String verificationCode;
    private Instant createdAt;
    private Instant verifiedAt;
    private RecoverySessionStatus status;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public RecoverySessionStatus getStatus() {
        return status;
    }

    public void setStatus(RecoverySessionStatus status) {
        this.status = status;
    }
}
