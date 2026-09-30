package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 审计查询：按账号与时间范围过滤，事件按日志顺序返回，可完整还原状态演进。
 */
@Service
public class AuditQueryService {

    private final EventLog eventLog;

    public AuditQueryService(EventLog eventLog) {
        this.eventLog = eventLog;
    }

    public List<LifecycleEvent> byAccount(String accountId) {
        return eventLog.readAll().stream()
                .filter(event -> accountId.equals(event.accountId()))
                .toList();
    }

    public List<LifecycleEvent> byAccountAndTime(String accountId, Instant from, Instant to) {
        return byAccount(accountId).stream()
                .filter(event -> from == null || !event.occurredAt().isBefore(from))
                .filter(event -> to == null || !event.occurredAt().isAfter(to))
                .toList();
    }

    public List<LifecycleEvent> all() {
        return eventLog.readAll();
    }
}
