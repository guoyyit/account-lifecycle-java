package com.github.guoyyit.accountlifecyclejava.error;

/**
 * 生命周期治理相关错误的基类。
 */
public class LifecycleException extends RuntimeException {

    public LifecycleException(String message) {
        super(message);
    }
}
