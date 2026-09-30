package com.github.guoyyit.accountlifecyclejava.engine;

public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }
}
