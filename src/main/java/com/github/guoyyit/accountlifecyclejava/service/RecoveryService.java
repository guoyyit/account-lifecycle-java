package com.github.guoyyit.accountlifecyclejava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.domain.RecoverySession;
import com.github.guoyyit.accountlifecyclejava.domain.RecoverySessionStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.error.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.error.VerificationFailedException;
import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

/**
 * 回收账号的验证恢复。请求-验证全程事件化：
 * RECOVERY_VERIFIED 是唯一的完成标记，崩溃后按该标记恢复，绝不留下半完成状态。
 */
@Service
public class RecoveryService {

    static final Duration SESSION_TTL = Duration.ofMinutes(15);

    private final EventLog eventLog;
    private final AccountRepository accounts;
    private final IdempotentEvents idempotentEvents;
    private final TransitionEvents transitionEvents;
    private final PolicyService policyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, RecoverySession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionByKey = new ConcurrentHashMap<>();

    public RecoveryService(EventLog eventLog,
                           AccountRepository accounts,
                           IdempotentEvents idempotentEvents,
                           TransitionEvents transitionEvents,
                           PolicyService policyService,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.eventLog = eventLog;
        this.accounts = accounts;
        this.idempotentEvents = idempotentEvents;
        this.transitionEvents = transitionEvents;
        this.policyService = policyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        for (LifecycleEvent event : eventLog.readAll()) {
            if (event.type() == EventType.RECOVERY_REQUESTED
                    || event.type() == EventType.RECOVERY_COMPLETED
                    || event.type() == EventType.RECOVERY_FAILED) {
                apply(event);
            }
        }
    }

    public RecoverySession requestRecovery(String accountId, String idempotencyKey) {
        Lock lock = accounts.lockFor(accountId);
        lock.lock();
        try {
            Account account = accounts.require(accountId);
            if (account.getStatus() != AccountStatus.RECYCLED) {
                throw new LifecycleException(
                        "只有已回收账号可以发起恢复，当前状态: " + account.getStatus());
            }
            if (idempotencyKey != null) {
                String existingSessionId = sessionByKey.get(idempotencyKey);
                if (existingSessionId != null) {
                    return sessions.get(existingSessionId);
                }
            }
            Instant now = clock.instant();
            RecoverySession session = new RecoverySession();
            session.setSessionId(UUID.randomUUID().toString());
            session.setAccountId(accountId);
            session.setIdempotencyKey(idempotencyKey);
            session.setVerificationCode(generateCode());
            session.setCreatedAt(now);
            session.setStatus(RecoverySessionStatus.PENDING);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("sessionId", session.getSessionId());
            detail.put("code", session.getVerificationCode());
            detail.put("ttl", SESSION_TTL.toString());
            idempotentEvents.appendIdempotent(
                    "recovery:request:" + keyOr(idempotencyKey, session.getSessionId()),
                    () -> new LifecycleEvent(
                            UUID.randomUUID().toString(),
                            EventType.RECOVERY_REQUESTED,
                            accountId, now,
                            AccountStatus.RECYCLED.name(),
                            AccountStatus.RECYCLED.name(),
                            null,
                            writeJson(detail),
                            idempotencyKey));
            sessions.put(session.getSessionId(), session);
            if (idempotencyKey != null) {
                sessionByKey.put(idempotencyKey, session.getSessionId());
            }
            return session;
        } finally {
            lock.unlock();
        }
    }

    public RecoverySession completeRecovery(String sessionId, String code) {
        RecoverySession session = sessions.get(sessionId);
        if (session == null) {
            throw new VerificationFailedException("恢复会话不存在: " + sessionId);
        }
        Lock lock = accounts.lockFor(session.getAccountId());
        lock.lock();
        try {
            // 已完成的会话重复提交：幂等返回既有结果，绝不再次应用迁移。
            if (session.getStatus() != RecoverySessionStatus.PENDING) {
                return session;
            }
            Instant now = clock.instant();
            if (now.isAfter(session.getCreatedAt().plus(SESSION_TTL))) {
                return fail(session, "SESSION_EXPIRED", now);
            }
            if (code == null || !code.equals(session.getVerificationCode())) {
                return fail(session, "BAD_CODE", now);
            }
            Account account = accounts.require(session.getAccountId());
            if (account.getStatus() != AccountStatus.RECYCLED) {
                // 恢复途中账号已被其他流程迁移：拒绝并记录，避免覆盖确定状态。
                return fail(session, "ACCOUNT_NOT_RECYCLED", now);
            }
            GroupPolicy policy =
                    policyService.effectivePolicy(account.getGroupName(), now);
            Instant expiresAt = now.plus(policy.credentialValidity());
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("sessionId", sessionId);
            detail.put("credentialExpiresAt", expiresAt.toString());
            detail.put("lastActivityAt", now.toString());
            // 单个 RECOVERY_COMPLETED 事件原子提交“验证通过+新凭据+状态迁移”：
            // 崩溃在 fsync 前 -> 事件不存在，重放仍为 PENDING + RECYCLED，可整体重试；
            // 崩溃在 fsync 后 -> 重放一次性还原 ACTIVE 与新凭据，绝不留下半成品。
            transitionEvents.transition(account,
                    AccountStatus.ACTIVE, TransitionReason.RECOVERED, now, detail,
                    EventType.RECOVERY_COMPLETED);
            session.setStatus(RecoverySessionStatus.SUCCEEDED);
            session.setVerifiedAt(now);
            return session;
        } finally {
            lock.unlock();
        }
    }

    public Optional<RecoverySession> findSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    private RecoverySession fail(RecoverySession session, String failureCode, Instant at) {
        Map<String, String> detail = Map.of(
                "sessionId", session.getSessionId(),
                "failure", failureCode);
        idempotentEvents.append(new LifecycleEvent(
                UUID.randomUUID().toString(),
                EventType.RECOVERY_FAILED,
                session.getAccountId(), at,
                AccountStatus.RECYCLED.name(),
                AccountStatus.RECYCLED.name(),
                null,
                writeJson(detail),
                null));
        session.setStatus(failureCode.equals("SESSION_EXPIRED")
                ? RecoverySessionStatus.EXPIRED : RecoverySessionStatus.FAILED);
        throw new VerificationFailedException(
                "恢复验证失败: " + describeFailure(failureCode));
    }

    private String describeFailure(String code) {
        return switch (code) {
            case "BAD_CODE" -> "验证码错误";
            case "SESSION_EXPIRED" -> "恢复会话已过期";
            case "ACCOUNT_NOT_RECYCLED" -> "账号当前不是已回收状态";
            default -> code;
        };
    }

    private String keyOr(String key, String fallback) {
        return key == null ? fallback : key;
    }

    private String generateCode() {
        // 本地服务、无真实凭据：生成 6 位数字验证码，仅用于验证流程演示。
        int code = Math.abs(UUID.randomUUID().hashCode()) % 1_000_000;
        return String.format("%06d", code);
    }

    private String writeJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void apply(LifecycleEvent event) {
        try {
            JsonNode detail = event.detail() == null ? null
                    : objectMapper.readTree(event.detail());
            switch (event.type()) {
                case RECOVERY_REQUESTED -> {
                    RecoverySession session = new RecoverySession();
                    session.setSessionId(detail.get("sessionId").asText());
                    session.setAccountId(event.accountId());
                    session.setIdempotencyKey(event.idempotencyKey());
                    session.setVerificationCode(detail.get("code").asText());
                    session.setCreatedAt(event.occurredAt());
                    session.setStatus(RecoverySessionStatus.PENDING);
                    sessions.put(session.getSessionId(), session);
                    if (event.idempotencyKey() != null) {
                        sessionByKey.put(event.idempotencyKey(), session.getSessionId());
                    }
                }
                case RECOVERY_COMPLETED -> {
                    String sessionId = detail.get("sessionId").asText();
                    RecoverySession session = sessions.get(sessionId);
                    if (session != null && session.getStatus()
                            == RecoverySessionStatus.PENDING) {
                        session.setStatus(RecoverySessionStatus.SUCCEEDED);
                        session.setVerifiedAt(event.occurredAt());
                    }
                }
                case RECOVERY_FAILED -> {
                    String sessionId = detail.get("sessionId").asText();
                    String failure = detail.get("failure").asText();
                    RecoverySession session = sessions.get(sessionId);
                    if (session != null) {
                        session.setStatus("SESSION_EXPIRED".equals(failure)
                                ? RecoverySessionStatus.EXPIRED
                                : RecoverySessionStatus.FAILED);
                    }
                }
                default -> {
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("重放恢复事件失败: " + event.eventId(), e);
        }
    }
}
