package com.github.guoyyit.accountlifecyclejava.lifecycle;

public class GracePeriodExpiredException extends InvalidTransitionException {

    public static final String CODE = "GRACE_PERIOD_EXPIRED";

    public GracePeriodExpiredException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return CODE;
    }
}
