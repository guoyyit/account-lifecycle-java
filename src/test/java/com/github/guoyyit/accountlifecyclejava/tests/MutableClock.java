package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.engine.Clock;

import java.util.concurrent.atomic.AtomicLong;

public class MutableClock implements Clock {

    private final AtomicLong now;

    public MutableClock(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    public static MutableClock startAt(long millis) {
        return new MutableClock(millis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void setMillis(long millis) {
        now.set(millis);
    }

    public void advanceMillis(long delta) {
        now.addAndGet(delta);
    }

    public void advanceSeconds(long seconds) {
        advanceMillis(seconds * 1_000L);
    }
}
