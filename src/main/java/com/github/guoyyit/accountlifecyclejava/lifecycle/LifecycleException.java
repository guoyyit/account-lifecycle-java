package com.github.guoyyit.accountlifecyclejava.lifecycle;

public class LifecycleException extends RuntimeException {
    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Throwable cause) {
        super(message, cause);
    }

    public String code() {
        return "LIFECYCLE_ERROR";
    }
}
