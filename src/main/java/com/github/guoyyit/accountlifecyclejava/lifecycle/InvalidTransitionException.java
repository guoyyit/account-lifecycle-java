package com.github.guoyyit.accountlifecyclejava.lifecycle;

public class InvalidTransitionException extends LifecycleException {
    public InvalidTransitionException(String message) {
        super(message);
    }
}
