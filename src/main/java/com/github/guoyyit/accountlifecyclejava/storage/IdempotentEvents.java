package com.github.guoyyit.accountlifecyclejava.storage;

import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 幂等键去重的事件追加器：相同 idempotencyKey 的重复请求直接返回首次事件。
 */
@Component
public class IdempotentEvents {

    private final EventLog eventLog;
    private final Map<String, LifecycleEvent> seen = new HashMap<>();
    private final ObjectProvider<AccountRepository> accounts;

    public IdempotentEvents(EventLog eventLog, ObjectProvider<AccountRepository> accounts) {
        this.eventLog = eventLog;
        this.accounts = accounts;
    }

    @PostConstruct
    public void init() {
        AccountRepository repository = accounts.getIfAvailable();
        for (LifecycleEvent event : eventLog.readAll()) {
            if (event.idempotencyKey() != null) {
                seen.putIfAbsent(event.idempotencyKey(), event);
            }
            if (repository != null) {
                repository.applyEvent(event);
            }
        }
    }

    /**
     * 相同幂等键的重复请求返回首次落盘的事件（请求-一次语义）。
     * creator 只在键缺失时、锁内执行，避免并发双写。
     */
    public LifecycleEvent appendIdempotent(String idempotencyKey, Supplier<LifecycleEvent> creator) {
        if (idempotencyKey == null) {
            return append(creator.get());
        }
        LifecycleEvent existing = seen.get(idempotencyKey);
        if (existing != null) {
            return existing;
        }
        synchronized (seen) {
            existing = seen.get(idempotencyKey);
            if (existing != null) {
                return existing;
            }
            LifecycleEvent event = creator.get();
            eventLog.append(event);
            seen.put(idempotencyKey, event);
            accounts.ifAvailable(repo -> repo.applyEvent(event));
            return event;
        }
    }

    public LifecycleEvent append(LifecycleEvent event) {
        eventLog.append(event);
        accounts.ifAvailable(repo -> repo.applyEvent(event));
        return event;
    }

    public boolean wasProcessed(String idempotencyKey) {
        return idempotencyKey != null && seen.containsKey(idempotencyKey);
    }
}
