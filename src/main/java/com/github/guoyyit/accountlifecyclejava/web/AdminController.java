package com.github.guoyyit.accountlifecyclejava.web;

import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.service.AuditQueryService;
import com.github.guoyyit.accountlifecyclejava.service.LifecycleScanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final LifecycleScanner scanner;
    private final AuditQueryService auditQueryService;
    private final Clock clock;

    public AdminController(LifecycleScanner scanner,
                           AuditQueryService auditQueryService,
                           Clock clock) {
        this.scanner = scanner;
        this.auditQueryService = auditQueryService;
        this.clock = clock;
    }

    @PostMapping("/scan")
    public List<Account> scanNow() {
        return scanner.scanOnce(clock.instant());
    }

    @GetMapping("/audit")
    public List<LifecycleEvent> audit() {
        return auditQueryService.all();
    }
}
