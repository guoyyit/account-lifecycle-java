package com.github.guoyyit.accountlifecyclejava.event;

import java.time.Instant;

/**
 * 不可变审计事件。一行一条，顺序追加到本地日志；事件顺序与最终状态一致。
 */
public record LifecycleEvent(
        String eventId,
        EventType type,
        String accountId,
        Instant occurredAt,
        String fromStatus,
        String toStatus,
        String reason,
        String detail,
        String idempotencyKey
) {
}
