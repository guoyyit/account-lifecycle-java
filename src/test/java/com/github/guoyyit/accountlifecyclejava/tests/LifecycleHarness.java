package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.engine.AccountService;
import com.github.guoyyit.accountlifecyclejava.engine.ActivityService;
import com.github.guoyyit.accountlifecyclejava.engine.AuditQueryService;
import com.github.guoyyit.accountlifecyclejava.engine.CredentialService;
import com.github.guoyyit.accountlifecyclejava.engine.PolicyService;
import com.github.guoyyit.accountlifecyclejava.engine.RecoveryService;
import com.github.guoyyit.accountlifecyclejava.engine.ScanCoordinator;
import com.github.guoyyit.accountlifecyclejava.engine.StateMachine;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;
import com.github.guoyyit.accountlifecyclejava.store.StoreRecovery;

import java.nio.file.Path;
import java.time.Duration;

public class LifecycleHarness {

    public final Path dir;
    public final MutableClock clock;
    public final DurableLog log;
    public final LifecycleStore store;
    public final StateMachine stateMachine;
    public final PolicyService policyService;
    public final AccountService accountService;
    public final ActivityService activityService;
    public final CredentialService credentialService;
    public final RecoveryService recoveryService;
    public final ScanCoordinator scanCoordinator;
    public final AuditQueryService auditQueryService;

    public LifecycleHarness(Path dir, MutableClock clock, PolicyService.Defaults defaults) {
        this.dir = dir;
        this.clock = clock;
        this.log = new DurableLog(dir, clock);
        this.store = new LifecycleStore();
        this.log.setApplier(store::apply);
        new StoreRecovery(log, store).recover();
        this.stateMachine = new StateMachine();
        this.policyService = new PolicyService(log, store, clock, defaults);
        this.policyService.seedDefaults();
        this.accountService = new AccountService(log, store, stateMachine, policyService, clock);
        this.activityService = new ActivityService(log, store, stateMachine, clock);
        this.credentialService = new CredentialService(log, store, stateMachine, clock);
        this.recoveryService = new RecoveryService(log, store, stateMachine, policyService, clock);
        this.scanCoordinator = new ScanCoordinator(log, store, stateMachine, policyService,
                recoveryService, clock);
        this.auditQueryService = new AuditQueryService(log);
    }

    public static PolicyService.Defaults defaults(Duration dormancy, Duration grace,
                                                  Duration buffer, Duration recoveryTtl) {
        return new PolicyService.Defaults(dormancy, grace, buffer, Duration.ZERO, recoveryTtl);
    }

    public LifecycleHarness reopen() {
        return new LifecycleHarness(dir, clock, new PolicyService.Defaults(
                Duration.ofDays(90), Duration.ofDays(7), Duration.ofDays(30), Duration.ZERO,
                Duration.ofHours(24)));
    }
}
