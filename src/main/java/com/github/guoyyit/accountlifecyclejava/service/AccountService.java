package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.domain.TransitionReason;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.locks.Lock;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final IdempotentEvents idempotentEvents;
    private final PolicyService policyService;
    private final TransitionEvents transitionEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AccountService(AccountRepository accounts,
                          IdempotentEvents idempotentEvents,
                          PolicyService policyService,
                          TransitionEvents transitionEvents,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.accounts = accounts;
        this.idempotentEvents = idempotentEvents;
        this.policyService = policyService;
        this.transitionEvents = transitionEvents;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Account register(String accountId, String groupName, String idempotencyKey) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        Lock lock = accounts.lockFor(accountId);
        lock.lock();
        try {
            Account existing = accounts.find(accountId).orElse(null);
            if (existing != null) {
                return existing;
            }
            Instant now = clock.instant();
            String requestedGroup = groupName == null || groupName.isBlank()
                    ? PolicyService.DEFAULT_GROUP : groupName;
            String effectiveGroup = policyService.hasGroup(requestedGroup)
                    ? requestedGroup : PolicyService.DEFAULT_GROUP;
            GroupPolicy policy = policyService.effectivePolicy(effectiveGroup, now);
            Instant credentialExpiresAt = now.plus(policy.credentialValidity());
            Map<String, String> detail = Map.of(
                    "groupName", effectiveGroup,
                    "credentialExpiresAt", credentialExpiresAt.toString());
            idempotentEvents.appendIdempotent(
                    idempotencyKey, () -> new LifecycleEvent(
                            UUID.randomUUID().toString(),
                            EventType.ACCOUNT_REGISTERED,
                            accountId,
                            now,
                            null, AccountStatus.ACTIVE.name(),
                            TransitionReason.REGISTERED.name(),
                            writeJson(detail),
                            idempotencyKey));
            return accounts.require(accountId);
        } finally {
            lock.unlock();
        }
    }

    public Account disable(String accountId, String reason) {
        Lock lock = accounts.lockFor(accountId);
        lock.lock();
        try {
            Account account = accounts.require(accountId);
            if (account.getStatus() == AccountStatus.DISABLED) {
                return account;
            }
            return transitionEvents.transition(account, AccountStatus.DISABLED,
                    TransitionReason.DISABLED, clock.instant(),
                    Map.of("requestedReason", reason == null ? "" : reason));
        } finally {
            lock.unlock();
        }
    }

    public Account get(String accountId) {
        return accounts.require(accountId);
    }

    public List<Account> list() {
        return List.copyOf(accounts.findAll());
    }

    private String writeJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
