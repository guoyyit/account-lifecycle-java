package com.github.guoyyit.accountlifecyclejava.web;

import com.github.guoyyit.accountlifecyclejava.domain.RecoverySession;
import com.github.guoyyit.accountlifecyclejava.service.RecoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recovery")
public class RecoveryController {

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    public record RecoveryRequest(String idempotencyKey) {
    }

    public record VerificationRequest(String code) {
    }

    @PostMapping("/{accountId}")
    public RecoverySession request(@PathVariable String accountId,
                                   @RequestBody(required = false) RecoveryRequest request) {
        String key = request == null ? null : request.idempotencyKey();
        return recoveryService.requestRecovery(accountId, key);
    }

    @PostMapping("/sessions/{sessionId}:complete")
    public RecoverySession complete(@PathVariable String sessionId,
                                    @RequestBody VerificationRequest request) {
        return recoveryService.completeRecovery(sessionId, request.code());
    }

    @GetMapping("/sessions/{sessionId}")
    public RecoverySession get(@PathVariable String sessionId) {
        return recoveryService.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("恢复会话不存在: " + sessionId));
    }
}
