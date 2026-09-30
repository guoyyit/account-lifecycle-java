package com.github.guoyyit.accountlifecyclejava.web;

import com.github.guoyyit.accountlifecyclejava.engine.AccountService;
import com.github.guoyyit.accountlifecyclejava.engine.ActivityService;
import com.github.guoyyit.accountlifecyclejava.engine.AuditQueryService;
import com.github.guoyyit.accountlifecyclejava.engine.CredentialService;
import com.github.guoyyit.accountlifecyclejava.engine.PolicyService;
import com.github.guoyyit.accountlifecyclejava.engine.RecoveryService;
import com.github.guoyyit.accountlifecyclejava.engine.ScanCoordinator;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;
import com.github.guoyyit.accountlifecyclejava.model.Account;
import com.github.guoyyit.accountlifecyclejava.model.AuditEntry;
import com.github.guoyyit.accountlifecyclejava.model.AuditQuery;
import com.github.guoyyit.accountlifecyclejava.model.PendingRecovery;
import com.github.guoyyit.accountlifecyclejava.model.Policy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lifecycle")
public class LifecycleController {

    private final AccountService accountService;
    private final ActivityService activityService;
    private final CredentialService credentialService;
    private final RecoveryService recoveryService;
    private final PolicyService policyService;
    private final ScanCoordinator scanCoordinator;
    private final AuditQueryService auditQueryService;

    public LifecycleController(AccountService accountService, ActivityService activityService,
                               CredentialService credentialService, RecoveryService recoveryService,
                               PolicyService policyService, ScanCoordinator scanCoordinator,
                               AuditQueryService auditQueryService) {
        this.accountService = accountService;
        this.activityService = activityService;
        this.credentialService = credentialService;
        this.recoveryService = recoveryService;
        this.policyService = policyService;
        this.scanCoordinator = scanCoordinator;
        this.auditQueryService = auditQueryService;
    }

    @PostMapping("/accounts")
    public Map<String, Object> register(@RequestBody Map<String, Object> request) {
        String accountId = requireString(request, "accountId");
        String group = (String) request.getOrDefault("group", PolicyService.DEFAULT_GROUP);
        Duration credentialTtl = parseDuration(request.get("credentialTtl"));
        Duration accountTtl = parseDuration(request.get("accountTtl"));
        Account account = accountService.register(accountId, group, credentialTtl, accountTtl);
        return toAccountView(account);
    }

    @PostMapping("/accounts/{id}/activity")
    public Map<String, Object> reportActivity(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> request) {
        Long activityAt = request == null ? null : parseLong(request.get("activityAtMillis"));
        if (activityAt == null) {
            activityService.report(id);
        } else {
            activityService.report(id, activityAt);
        }
        return Map.of("accountId", id, "accepted", true);
    }

    @PostMapping("/accounts/{id}/credential-renewal")
    public Map<String, Object> renewCredential(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> request) {
        Duration ttl = parseDuration(request == null ? null : request.get("ttl"));
        if (ttl == null) {
            throw new LifecycleException("ttl 不能为空，格式为 ISO-8601，例如 P90D");
        }
        credentialService.renew(id, ttl);
        return Map.of("accountId", id, "renewed", true);
    }

    @PostMapping("/accounts/{id}/recoveries")
    public Map<String, Object> startRecovery(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> request) {
        String requestId = request == null ? null : (String) request.get("requestId");
        PendingRecovery recovery = recoveryService.startRecovery(id, requestId);
        return toRecoveryView(recovery);
    }

    @PostMapping("/recoveries/{requestId}/verification")
    public Map<String, Object> verifyRecovery(@PathVariable String requestId,
                                              @RequestBody Map<String, Object> request) {
        boolean passed = Boolean.TRUE.equals(request.get("passed"));
        String failReason = (String) request.get("failReason");
        return toRecoveryView(recoveryService.verify(requestId, passed, failReason));
    }

    @PostMapping("/recoveries/{requestId}/completion")
    public Map<String, Object> completeRecovery(@PathVariable String requestId) {
        return toRecoveryView(recoveryService.complete(requestId));
    }

    @GetMapping("/recoveries/{requestId}")
    public Map<String, Object> getRecovery(@PathVariable String requestId) {
        return toRecoveryView(recoveryService.get(requestId));
    }

    @PutMapping("/groups/{group}/policy")
    public Map<String, Object> putPolicy(@PathVariable String group,
                                         @RequestBody Map<String, Object> request) {
        Policy policy = policyService.putPolicy(group,
                requireDuration(request, "dormancyThreshold"),
                requireDuration(request, "credentialGracePeriod"),
                requireDuration(request, "dormantBufferPeriod"),
                parseDuration(request.get("accountMaxLifetime")),
                requireDuration(request, "recoveryPendingTtl"),
                (String) request.get("reason"));
        return toPolicyView(policy);
    }

    @GetMapping("/groups/{group}/policy")
    public Map<String, Object> getPolicy(@PathVariable String group) {
        return toPolicyView(policyService.effectivePolicy(group));
    }

    @PostMapping("/scan")
    public Map<String, Object> scan() {
        int transitions = scanCoordinator.scanOnce();
        return Map.of("transitionsApplied", transitions);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(required = false) String accountId,
                                           @RequestParam(required = false) Long fromMillis,
                                           @RequestParam(required = false) Long toMillis) {
        AuditQuery query = new AuditQuery(accountId, fromMillis, toMillis);
        return auditQueryService.query(query).stream().map(LifecycleController::toAuditView).toList();
    }

    @GetMapping("/accounts/{id}/audit")
    public List<Map<String, Object>> accountAudit(@PathVariable String id) {
        return auditQueryService.byAccount(id).stream()
                .map(LifecycleController::toAuditView).toList();
    }

    private static String requireString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new LifecycleException(key + " 不能为空");
        }
        return text;
    }

    private static Duration requireDuration(Map<String, Object> request, String key) {
        Duration value = parseDuration(request.get(key));
        if (value == null) {
            throw new LifecycleException(key + " 不能为空，格式为 ISO-8601，例如 P30D");
        }
        return value;
    }

    private static Duration parseDuration(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "0".equals(text)) {
            return null;
        }
        return Duration.parse(text);
    }

    private static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Map<String, Object> toAccountView(Account account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", account.id());
        view.put("group", account.group());
        view.put("state", account.state().name());
        view.put("stateReason", account.stateReason().name());
        view.put("stateEnteredAtMillis", account.stateEnteredAtMillis());
        view.put("createdAtMillis", account.createdAtMillis());
        view.put("lastActivityAtMillis", account.lastActivityAtMillis());
        view.put("credentialExpiresAtMillis", account.credentialExpiresAtMillis());
        view.put("accountExpiresAtMillis", account.accountExpiresAtMillis());
        view.put("version", account.version());
        return view;
    }

    private static Map<String, Object> toRecoveryView(PendingRecovery recovery) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", recovery.requestId());
        view.put("accountId", recovery.accountId());
        view.put("status", recovery.status().name());
        view.put("createdAtMillis", recovery.createdAtMillis());
        view.put("verifiedAtMillis", recovery.verifiedAtMillis());
        view.put("failReason", recovery.failReason());
        return view;
    }

    private static Map<String, Object> toPolicyView(Policy policy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("group", policy.group());
        view.put("revision", policy.revision());
        view.put("dormancyThreshold", policy.dormancyThreshold().toString());
        view.put("credentialGracePeriod", policy.credentialGracePeriod().toString());
        view.put("dormantBufferPeriod", policy.dormantBufferPeriod().toString());
        view.put("accountMaxLifetime", policy.accountMaxLifetime().toString());
        view.put("recoveryPendingTtl", policy.recoveryPendingTtl().toString());
        view.put("createdAtMillis", policy.createdAtMillis());
        view.put("reason", policy.reason());
        return view;
    }

    private static Map<String, Object> toAuditView(AuditEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sequence", entry.sequence());
        view.put("timestampMillis", entry.timestampMillis());
        view.put("accountId", entry.accountId());
        view.put("eventType", entry.eventType());
        view.put("detail", entry.detail());
        return view;
    }
}
