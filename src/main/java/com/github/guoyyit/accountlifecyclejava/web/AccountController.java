package com.github.guoyyit.accountlifecyclejava.web;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.service.AccountService;
import com.github.guoyyit.accountlifecyclejava.service.ActivityService;
import com.github.guoyyit.accountlifecyclejava.service.AuditQueryService;
import com.github.guoyyit.accountlifecyclejava.service.CredentialService;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final ActivityService activityService;
    private final CredentialService credentialService;
    private final AuditQueryService auditQueryService;

    public AccountController(AccountService accountService,
                             ActivityService activityService,
                             CredentialService credentialService,
                             AuditQueryService auditQueryService) {
        this.accountService = accountService;
        this.activityService = activityService;
        this.credentialService = credentialService;
        this.auditQueryService = auditQueryService;
    }

    public record RegisterRequest(String accountId, String groupName, String idempotencyKey) {
    }

    public record ActivityRequest(Instant at, String idempotencyKey) {
    }

    @PostMapping
    public Account register(@RequestBody RegisterRequest request) {
        return accountService.register(request.accountId(), request.groupName(),
                request.idempotencyKey());
    }

    @GetMapping
    public List<Account> list() {
        return accountService.list();
    }

    @GetMapping("/{accountId}")
    public Account get(@PathVariable String accountId) {
        return accountService.get(accountId);
    }

    @PostMapping("/{accountId}/activity")
    public Account reportActivity(@PathVariable String accountId,
                                  @RequestBody ActivityRequest request) {
        return activityService.report(accountId, request.at(), request.idempotencyKey());
    }

    @PostMapping("/{accountId}/credential:renew")
    public Account renewCredential(@PathVariable String accountId,
                                   @RequestParam(required = false) String idempotencyKey) {
        return credentialService.renewCredential(accountId, idempotencyKey);
    }

    @PostMapping("/{accountId}:disable")
    public Account disable(@PathVariable String accountId,
                           @RequestParam(required = false) String reason) {
        return accountService.disable(accountId, reason);
    }

    @GetMapping("/{accountId}/audit")
    public List<LifecycleEvent> audit(@PathVariable String accountId,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to) {
        if (from == null && to == null) {
            return auditQueryService.byAccount(accountId);
        }
        return auditQueryService.byAccountAndTime(accountId, from, to);
    }
}
