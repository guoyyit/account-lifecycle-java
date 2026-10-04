package com.github.guoyyit.accountlifecyclejava.lifecycle;

public class GracePeriodExpiredException extends LifecycleException {
    public GracePeriodExpiredException(String message) {
        super(message);
    }
}
