package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.JsonCodec;
import com.github.guoyyit.accountlifecyclejava.event.Payloads;
import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.VerificationFailedException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.RecoveryStatus;
import com.github.guoyyit.accountlifecyclejava.model.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.model.VerifyRejectReason;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;

import java.util.UUID;

public class RecoveryService {

    private final DurableLog log;
    private final LifecycleStore store;
    private final StateMachine stateMachine;
    private final PolicyService policyService;
    private final Clock clock;

    public RecoveryService(DurableLog log, LifecycleStore store, StateMachine stateMachine,
                           PolicyService policyService, Clock clock) {
        this.log = log;
        this.store = store;
        this.stateMachine = stateMachine;
        this.policyService = policyService;
        this.clock = clock;
    }

    public PendingRecovery startRecovery(String accountId, String requestId) {
        synchronized (AccountService.lockFor(accountId)) {
            Account account = store.getAccount(accountId);
            if (account == null) {
                throw new LifecycleException("账号不存在: " + accountId);
            }
            if (account.state() != AccountState.RECYCLED) {
                throw new InvalidTransitionException(
                        "仅已回收账号可以发起恢复，当前状态: " + account.state());
            }
            if (requestId != null && !requestId.isBlank()) {
                PendingRecovery existing = store.getRecovery(requestId);
                if (existing != null) {
                    if (!existing.accountId().equals(accountId)) {
                        throw new LifecycleException("恢复请求 ID 已被其它账号占用: " + requestId);
                    }
                    return existing;
                }
            }
            PendingRecovery open = store.findRecoveryByAccount(accountId);
            if (open != null) {
                return open;
            }
            String id = requestId == null || requestId.isBlank()
                    ? UUID.randomUUID().toString() : requestId;
            long now = clock.nowMillis();
            long ttl = policyService.effectivePolicy(account.group()).recoveryPendingTtl().toMillis();
            String verificationToken = UUID.randomUUID().toString();
            Payloads.RecoveryStarted payload =
                    new Payloads.RecoveryStarted(id, accountId, ttl, now,
                            verificationToken, now + ttl);
            log.append(EventType.RECOVERY_STARTED, accountId, JsonCodec.toJson(payload));
            return store.getRecovery(id);
        }
    }

    public PendingRecovery verify(String requestId, String token) {
        PendingRecovery recovery = requireRecovery(requestId);
        synchronized (AccountService.lockFor(recovery.accountId())) {
            recovery = store.getRecovery(requestId);
            if (recovery.status() == RecoveryStatus.VERIFIED
                    || recovery.status() == RecoveryStatus.COMPLETED) {
                if (tokenMatches(recovery, token)) {
                    return recovery;
                }
                rejectAttempt(recovery, VerifyRejectReason.TOKEN_MISMATCH);
            }
            if (recovery.status() == RecoveryStatus.FAILED) {
                throw new InvalidTransitionException("恢复请求已失败，不能再次验证: " + requestId);
            }
            long now = clock.nowMillis();
            if (token == null || token.isBlank()) {
                rejectAttempt(recovery, VerifyRejectReason.TOKEN_MISSING);
            }
            if (!tokenMatches(recovery, token)) {
                rejectAttempt(recovery, VerifyRejectReason.TOKEN_MISMATCH);
            }
            Long tokenExpiresAt = recovery.tokenExpiresAtMillis();
            if (tokenExpiresAt != null && now >= tokenExpiresAt) {
                Payloads.RecoveryFailed payload = new Payloads.RecoveryFailed(requestId,
                        recovery.accountId(), RecoveryStatus.FAILED, "验证凭据已过期", now);
                log.append(EventType.RECOVERY_FAILED, recovery.accountId(),
                        JsonCodec.toJson(payload));
                throw new VerificationFailedException(VerifyRejectReason.TOKEN_EXPIRED,
                        "验证凭据已过期，恢复请求: " + requestId);
            }
            Payloads.RecoveryVerified payload =
                    new Payloads.RecoveryVerified(requestId, recovery.accountId(), now);
            log.append(EventType.RECOVERY_VERIFIED, recovery.accountId(),
                    JsonCodec.toJson(payload));
            return store.getRecovery(requestId);
        }
    }

    public PendingRecovery complete(String requestId) {
        PendingRecovery recovery = requireRecovery(requestId);
        synchronized (AccountService.lockFor(recovery.accountId())) {
            recovery = store.getRecovery(requestId);
            if (recovery.status() == RecoveryStatus.COMPLETED) {
                return recovery;
            }
            if (recovery.status() != RecoveryStatus.VERIFIED) {
                throw new InvalidTransitionException(
                        "恢复请求未通过验证，不能完成: " + recovery.status());
            }
            Account account = store.getAccount(recovery.accountId());
            if (account.state() != AccountState.RECYCLED) {
                throw new InvalidTransitionException(
                        "账号已不在回收状态，当前: " + account.state());
            }
            long now = clock.nowMillis();
            stateMachine.checkTransition(AccountState.RECYCLED, AccountState.ACTIVE);
            Payloads.RecoveryCompleted completed = new Payloads.RecoveryCompleted(requestId,
                    account.id(), AccountState.ACTIVE, RecoveryStatus.COMPLETED, now);
            log.append(EventType.RECOVERY_COMPLETED, account.id(),
                    JsonCodec.toJson(completed));
            return store.getRecovery(requestId);
        }
    }

    public PendingRecovery get(String requestId) {
        return requireRecovery(requestId);
    }

    private PendingRecovery requireRecovery(String requestId) {
        PendingRecovery recovery = store.getRecovery(requestId);
        if (recovery == null) {
            throw new LifecycleException("恢复请求不存在: " + requestId);
        }
        return recovery;
    }

    private boolean tokenMatches(PendingRecovery recovery, String token) {
        return token != null && recovery.verificationToken() != null
                && recovery.verificationToken().equals(token);
    }

    private void rejectAttempt(PendingRecovery recovery, VerifyRejectReason reason) {
        long now = clock.nowMillis();
        Payloads.RecoveryVerifyRejected payload = new Payloads.RecoveryVerifyRejected(
                recovery.requestId(), recovery.accountId(), reason, now);
        log.append(EventType.RECOVERY_VERIFY_REJECTED, recovery.accountId(),
                JsonCodec.toJson(payload));
        String message = switch (reason) {
            case TOKEN_MISSING -> "验证凭据缺失，恢复请求: " + recovery.requestId();
            case TOKEN_MISMATCH -> "验证凭据错误，恢复请求: " + recovery.requestId();
            case TOKEN_EXPIRED -> "验证凭据已过期，恢复请求: " + recovery.requestId();
        };
        throw new VerificationFailedException(reason, message);
    }
}
