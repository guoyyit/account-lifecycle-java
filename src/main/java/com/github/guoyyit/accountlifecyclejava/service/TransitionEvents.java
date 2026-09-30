package com.github.guoyyit.accountlifecyclejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 状态迁移的统一出口：先过状态机，再以单个 STATE_TRANSITION 事件提交。
 * 事件落盘（含 fsync）成功后才更新内存聚合，
 * 所以“迁移”在持久化层面是原子的——不存在写了一半的状态。
 */
@Component
public class TransitionEvents {

    private final AccountRepository accounts;
    private final IdempotentEvents idempotentEvents;
    private final StateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public TransitionEvents(AccountRepository accounts,
                            IdempotentEvents idempotentEvents,
                            StateMachine stateMachine,
                            ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.idempotentEvents = idempotentEvents;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    public Account transition(Account account,
                              AccountStatus target,
                              TransitionReason reason,
                              Instant at,
                              Map<String, String> extraDetail) {
        return transition(account, target, reason, at, extraDetail,
                EventType.STATE_TRANSITION);
    }

    public Account transition(Account account,
                              AccountStatus target,
                              TransitionReason reason,
                              Instant at,
                              Map<String, String> extraDetail,
                              EventType eventType) {
        stateMachine.assertTransition(account.getStatus(), target);
        Map<String, String> detail = extraDetail == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(extraDetail);
        String serializedDetail = detail.isEmpty() ? null : writeJson(detail);
        LifecycleEvent event = new LifecycleEvent(
                UUID.randomUUID().toString(),
                eventType,
                account.getId(),
                at,
                account.getStatus().name(),
                target.name(),
                reason.name(),
                serializedDetail,
                null);
        idempotentEvents.append(event);
        return accounts.require(account.getId());
    }

    public LifecycleEvent recordActivity(String accountId, Instant at,
                                         String idempotencyKey) {
        return idempotentEvents.appendIdempotent(idempotencyKey, () -> new LifecycleEvent(
                UUID.randomUUID().toString(),
                EventType.ACTIVITY_REPORTED,
                accountId,
                at,
                null, null, null,
                at.toString(),
                idempotencyKey));
    }

    public LifecycleEvent issueCredential(String accountId, Instant expiresAt, Instant at,
                                          String idempotencyKey) {
        Map<String, String> detail = Map.of("expiresAt", expiresAt.toString());
        return idempotentEvents.appendIdempotent(idempotencyKey, () -> new LifecycleEvent(
                UUID.randomUUID().toString(),
                EventType.CREDENTIAL_ISSUED,
                accountId,
                at,
                null, null, null,
                writeJson(detail),
                idempotencyKey));
    }

    private String writeJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
