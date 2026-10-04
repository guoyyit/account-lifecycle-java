package com.github.guoyyit.accountlifecyclejava.lifecycle;

public class RecoveryVerificationException extends InvalidTransitionException {

    private final String code;

    public RecoveryVerificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
