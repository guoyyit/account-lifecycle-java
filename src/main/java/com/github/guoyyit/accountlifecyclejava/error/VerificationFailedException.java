package com.github.guoyyit.accountlifecyclejava.error;

/**
 * 恢复验证失败（验证码错误、会话过期等）。
 */
public class VerificationFailedException extends LifecycleException {

    public VerificationFailedException(String message) {
        super(message);
    }
}
