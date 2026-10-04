package com.github.guoyyit.accountlifecyclejava.lifecycle;

public class InvalidTransitionException extends LifecycleException {
    public InvalidTransitionException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "INVALID_STATE_TRANSITION";
    }
}
