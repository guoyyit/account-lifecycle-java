package com.github.guoyyit.accountlifecyclejava.web;

import com.github.guoyyit.accountlifecyclejava.lifecycle.GracePeriodExpiredException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.lifecycle.RecoveryVerificationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class LifecycleExceptionHandler {

    @ExceptionHandler(GracePeriodExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleGracePeriodExpired(GracePeriodExpiredException ex) {
        return conflict(ex);
    }

    @ExceptionHandler(RecoveryVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleRecoveryVerification(RecoveryVerificationException ex) {
        return conflict(ex);
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransition(InvalidTransitionException ex) {
        return conflict(ex);
    }

    @ExceptionHandler(LifecycleException.class)
    public ResponseEntity<Map<String, Object>> handleLifecycle(LifecycleException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.code());
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static ResponseEntity<Map<String, Object>> conflict(LifecycleException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.code());
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
