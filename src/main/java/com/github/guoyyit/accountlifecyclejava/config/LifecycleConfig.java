package com.github.guoyyit.accountlifecyclejava.config;

import com.github.guoyyit.accountlifecyclejava.engine.AccountService;
import com.github.guoyyit.accountlifecyclejava.engine.ActivityService;
import com.github.guoyyit.accountlifecyclejava.engine.AuditQueryService;
import com.github.guoyyit.accountlifecyclejava.engine.Clock;
import com.github.guoyyit.accountlifecyclejava.engine.CredentialService;
import com.github.guoyyit.accountlifecyclejava.engine.PolicyService;
import com.github.guoyyit.accountlifecyclejava.engine.RecoveryService;
import com.github.guoyyit.accountlifecyclejava.engine.ScanCoordinator;
import com.github.guoyyit.accountlifecyclejava.engine.StateMachine;
import com.github.guoyyit.accountlifecyclejava.store.DurableLog;
import com.github.guoyyit.accountlifecyclejava.store.LifecycleStore;
import com.github.guoyyit.accountlifecyclejava.store.StoreRecovery;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleConfig {

    @Bean
    public Clock lifecycleClock() {
        return Clock.system();
    }

    @Bean
    public StateMachine stateMachine() {
        return new StateMachine();
    }

    @Bean
    public DurableLog durableLog(LifecycleProperties properties, Clock lifecycleClock) {
        return new DurableLog(Paths.get(properties.getDataDir()), lifecycleClock);
    }

    @Bean
    public LifecycleStore lifecycleStore() {
        return new LifecycleStore();
    }

    @Bean
    public StoreRecovery storeRecovery(DurableLog durableLog, LifecycleStore lifecycleStore) {
        durableLog.setApplier(lifecycleStore::apply);
        return new StoreRecovery(durableLog, lifecycleStore);
    }

    @Bean
    public PolicyService policyService(DurableLog durableLog, LifecycleStore lifecycleStore,
                                       Clock lifecycleClock, LifecycleProperties properties,
                                       StoreRecovery storeRecovery) {
        storeRecovery.recover();
        PolicyService.Defaults defaults = new PolicyService.Defaults(
                properties.getDefaultDormancyThreshold(),
                properties.getDefaultCredentialGracePeriod(),
                properties.getDefaultDormantBufferPeriod(),
                properties.getDefaultAccountMaxLifetime(),
                properties.getDefaultRecoveryPendingTtl());
        PolicyService service = new PolicyService(durableLog, lifecycleStore, lifecycleClock,
                defaults);
        service.seedDefaults();
        return service;
    }

    @Bean
    public AccountService accountService(DurableLog durableLog, LifecycleStore lifecycleStore,
                                         StateMachine stateMachine, PolicyService policyService,
                                         Clock lifecycleClock) {
        return new AccountService(durableLog, lifecycleStore, stateMachine, policyService,
                lifecycleClock);
    }

    @Bean
    public ActivityService activityService(DurableLog durableLog, LifecycleStore lifecycleStore,
                                           StateMachine stateMachine, Clock lifecycleClock) {
        return new ActivityService(durableLog, lifecycleStore, stateMachine, lifecycleClock);
    }

    @Bean
    public CredentialService credentialService(DurableLog durableLog, LifecycleStore lifecycleStore,
                                               StateMachine stateMachine, Clock lifecycleClock) {
        return new CredentialService(durableLog, lifecycleStore, stateMachine, lifecycleClock);
    }

    @Bean
    public RecoveryService recoveryService(DurableLog durableLog, LifecycleStore lifecycleStore,
                                           StateMachine stateMachine, PolicyService policyService,
                                           Clock lifecycleClock) {
        return new RecoveryService(durableLog, lifecycleStore, stateMachine, policyService,
                lifecycleClock);
    }

    @Bean
    public ScanCoordinator scanCoordinator(DurableLog durableLog, LifecycleStore lifecycleStore,
                                           StateMachine stateMachine, PolicyService policyService,
                                           RecoveryService recoveryService, Clock lifecycleClock) {
        return new ScanCoordinator(durableLog, lifecycleStore, stateMachine, policyService,
                recoveryService, lifecycleClock);
    }

    @Bean
    public AuditQueryService auditQueryService(DurableLog durableLog) {
        return new AuditQueryService(durableLog);
    }
}
