package com.github.guoyyit.accountlifecyclejava.store;

import com.github.guoyyit.accountlifecyclejava.event.Event;

public class StoreRecovery {

    private final DurableLog log;
    private final LifecycleStore store;

    public StoreRecovery(DurableLog log, LifecycleStore store) {
        this.log = log;
        this.store = store;
    }

    public long recover() {
        store.reset();
        long lastSequence = 0L;
        for (Event event : log.readAll()) {
            store.apply(event);
            lastSequence = event.sequence();
        }
        return lastSequence;
    }
}
