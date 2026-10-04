package com.github.guoyyit.accountlifecyclejava.lifecycle;

import com.github.guoyyit.accountlifecyclejava.model.VerifyRejectReason;

public class VerificationFailedException extends LifecycleException {

    private final VerifyRejectReason reason;

    public VerificationFailedException(VerifyRejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public VerifyRejectReason reason() {
        return reason;
    }
}
