package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.event.Event;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;

import java.util.List;

public class AuditQueryService {

    private final DurableLog log;

    public AuditQueryService(DurableLog log) {
        this.log = log;
    }

    public List<AuditEntry> query(AuditQuery query) {
        return log.readAll().stream()
                .filter(e -> query.accountId() == null || query.accountId().isBlank()
                        || query.accountId().equals(e.accountId()))
                .filter(e -> query.fromMillisInclusive() == null
                        || e.timestampMillis() >= query.fromMillisInclusive())
                .filter(e -> query.toMillisInclusive() == null
                        || e.timestampMillis() <= query.toMillisInclusive())
                .map(AuditQueryService::toEntry)
                .toList();
    }

    public List<AuditEntry> byAccount(String accountId) {
        return query(new AuditQuery(accountId, null, null));
    }

    private static AuditEntry toEntry(Event event) {
        return new AuditEntry(event.sequence(), event.timestampMillis(), event.accountId(),
                event.type().name(), event.payloadJson());
    }
}
