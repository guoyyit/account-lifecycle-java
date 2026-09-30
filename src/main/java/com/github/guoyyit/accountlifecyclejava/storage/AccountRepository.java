package com.github.guoyyit.accountlifecyclejava.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.time.Instant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 事件溯源账号仓储：启动时重放事件重建内存状态；
 * 每个账号一把条纹锁，保证同一账号上的判定与迁移串行。
 */
@Component
public class AccountRepository {

    private static final int LOCK_STRIPES = 64;

    private final ObjectMapper objectMapper;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Set<String> appliedEventIds = ConcurrentHashMap.newKeySet();
    private final Lock[] stripes;

    public AccountRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.stripes = new Lock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * 实时应用新事件，与启动重放走同一个 fold 函数。
     * 按 eventId 去重：同一事件重复投递（幂等键回放、崩溃后补做）只生效一次。
     */
    public void applyEvent(LifecycleEvent event) {
        if (appliedEventIds.add(event.eventId())) {
            apply(event);
        }
    }

    /**
     * 取账号上的专用锁。账号的每次判定-迁移全过程都在该锁内完成，
     * 因而上报与扫描交错时，任何一方看到的都是已确定的最新状态。
     */
    public Lock lockFor(String accountId) {
        return stripes[Math.floorMod(accountId.hashCode(), LOCK_STRIPES)];
    }

    public Optional<Account> find(String accountId) {
        return Optional.ofNullable(copy(accounts.get(accountId)));
    }

    public Collection<Account> findAll() {
        return accounts.values().stream().map(this::copy).toList();
    }

    public Account require(String accountId) {
        return find(accountId).orElseThrow(() ->
                new IllegalArgumentException("账号不存在: " + accountId));
    }

    private Account copy(Account source) {
        if (source == null) {
            return null;
        }
        Account target = new Account();
        target.setId(source.getId());
        target.setGroupName(source.getGroupName());
        target.setCreatedAt(source.getCreatedAt());
        target.setLastActivityAt(source.getLastActivityAt());
        target.setCredentialExpiresAt(source.getCredentialExpiresAt());
        target.setStatus(source.getStatus());
        target.setStatusReason(source.getStatusReason());
        target.setStatusEnteredAt(source.getStatusEnteredAt());
        target.setVersion(source.getVersion());
        target.setLastTransitionEventId(source.getLastTransitionEventId());
        return target;
    }

    private void apply(LifecycleEvent event) {
        switch (event.type()) {
            case ACCOUNT_REGISTERED -> applyRegistered(event);
            case ACTIVITY_REPORTED -> applyActivity(event);
            case CREDENTIAL_ISSUED -> applyCredentialIssued(event);
            case STATE_TRANSITION, RECOVERY_COMPLETED -> applyTransition(event);
            default -> {
            }
        }
    }

    private Account mutate(String accountId) {
        return accounts.computeIfAbsent(accountId, id -> {
            Account account = new Account();
            account.setId(id);
            account.setVersion(0);
            return account;
        });
    }

    private void applyRegistered(LifecycleEvent event) {
        JsonNode detail = readDetail(event);
        Account account = accounts.get(event.accountId());
        boolean fresh = account == null;
        if (fresh) {
            account = new Account();
            account.setId(event.accountId());
            account.setVersion(0);
        }
        if (!fresh && account.getStatus() != null) {
            // 正常顺序下注册只会出现一次；重放重复注册事件保持幂等。
            return;
        }
        account.setGroupName(textOr(detail, "groupName", "default"));
        account.setCreatedAt(event.occurredAt());
        Instant registeredAt = event.occurredAt();
        if (account.getLastActivityAt() == null
                || registeredAt.isAfter(account.getLastActivityAt())) {
            account.setLastActivityAt(registeredAt);
        }
        account.setCredentialExpiresAt(
                Instant.parse(detail.get("credentialExpiresAt").asText()));
        account.setStatus(AccountStatus.ACTIVE);
        account.setStatusReason(TransitionReason.REGISTERED);
        account.setStatusEnteredAt(event.occurredAt());
        account.setVersion(1);
        account.setLastTransitionEventId(event.eventId());
        if (fresh) {
            accounts.put(event.accountId(), account);
        }
    }

    private void applyActivity(LifecycleEvent event) {
        Account account = mutate(event.accountId());
        Instant at = Instant.parse(event.detail());
        if (account.getLastActivityAt() == null || at.isAfter(account.getLastActivityAt())) {
            account.setLastActivityAt(at);
        }
    }

    private void applyCredentialIssued(LifecycleEvent event) {
        Account account = mutate(event.accountId());
        JsonNode detail = readDetail(event);
        account.setCredentialExpiresAt(
                Instant.parse(detail.get("expiresAt").asText()));
    }

    private void applyTransition(LifecycleEvent event) {
        Account account = mutate(event.accountId());
        account.setStatus(AccountStatus.valueOf(event.toStatus()));
        account.setStatusReason(TransitionReason.valueOf(event.reason()));
        account.setStatusEnteredAt(event.occurredAt());
        account.setVersion(account.getVersion() + 1);
        account.setLastTransitionEventId(event.eventId());
        JsonNode detail = readDetail(event);
        if (detail != null && detail.has("credentialExpiresAt")
                && !detail.get("credentialExpiresAt").isNull()) {
            account.setCredentialExpiresAt(
                    Instant.parse(detail.get("credentialExpiresAt").asText()));
        }
        if (detail != null && detail.has("lastActivityAt")
                && !detail.get("lastActivityAt").isNull()) {
            account.setLastActivityAt(
                    Instant.parse(detail.get("lastActivityAt").asText()));
        }
    }

    private JsonNode readDetail(LifecycleEvent event) {
        if (event.detail() == null || event.detail().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(event.detail());
        } catch (IOException e) {
            throw new UncheckedIOException("重放事件失败: " + event.eventId(), e);
        }
    }

    private String textOr(JsonNode node, String field, String fallback) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return fallback;
    }
}
