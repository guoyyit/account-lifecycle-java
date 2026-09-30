package com.github.guoyyit.accountlifecyclejava.event;

public record Event(
        long sequence,
        long timestampMillis,
        EventType type,
        String accountId,
        String payloadJson
) {
}
