package com.github.guoyyit.accountlifecyclejava.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.accountlifecyclejava.domain.Account;
import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.service.AccountService;
import com.github.guoyyit.accountlifecyclejava.service.ActivityService;
import com.github.guoyyit.accountlifecyclejava.service.AuditQueryService;
import com.github.guoyyit.accountlifecyclejava.service.CredentialService;
import com.github.guoyyit.accountlifecyclejava.service.LifecycleScanner;
import com.github.guoyyit.accountlifecyclejava.service.PolicyService;
import com.github.guoyyit.accountlifecyclejava.service.RecoveryService;
import com.github.guoyyit.accountlifecyclejava.service.StateMachine;
import com.github.guoyyit.accountlifecyclejava.service.TransitionEvents;
import com.github.guoyyit.accountlifecyclejava.storage.AccountRepository;
import com.github.guoyyit.accountlifecyclejava.storage.FileEventLog;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * 纯手工装配的测试环境：事件日志落在临时目录，时钟可随意拨弄，
 * {@link #restart()} 用同一日志重建全部服务，模拟进程重启。
 */
public class TestHarness {

    /** 测试用固定起点，方便断言临界时刻。 */
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final Path logFile;
    private MutableClock clock;

    public EventLog eventLog;
    public ObjectMapper objectMapper;
    public IdempotentEvents idempotentEvents;
    public AccountRepository accounts;
    public PolicyService policyService;
    public StateMachine stateMachine;
    public TransitionEvents transitionEvents;
    public AccountService accountService;
    public ActivityService activityService;
    public CredentialService credentialService;
    public LifecycleScanner scanner;
    public RecoveryService recoveryService;
    public AuditQueryService auditQueryService;

    public TestHarness(Path dataDir) {
        this.logFile = dataDir.resolve("lifecycle-events.log");
        boot();
    }

    private void boot() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        clock = clock == null ? new MutableClock(T0) : clock;
        eventLog = new FileEventLog(logFile, objectMapper);
        accounts = new AccountRepository(objectMapper);
        idempotentEvents = new IdempotentEvents(eventLog,
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public AccountRepository getObject() {
                        return accounts;
                    }

                    @Override
                    public AccountRepository getIfAvailable() {
                        return accounts;
                    }
                });
        // 与 Spring 装配一致：先构造仓储，再让幂等组件重放日志填充它。
        idempotentEvents.init();
        policyService = new PolicyService(eventLog, idempotentEvents, clock,
                objectMapper, new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public com.github.guoyyit.accountlifecyclejava.config.LifecycleProperties
                    getObject() {
                        return new com.github.guoyyit.accountlifecyclejava.config.LifecycleProperties();
                    }

                    @Override
                    public com.github.guoyyit.accountlifecyclejava.config.LifecycleProperties
                    getIfAvailable() {
                        return getObject();
                    }
                });
        policyService.init();
        stateMachine = new StateMachine();
        transitionEvents = new TransitionEvents(
                accounts, idempotentEvents, stateMachine, objectMapper);
        accountService = new AccountService(accounts, idempotentEvents,
                policyService, transitionEvents, objectMapper, clock);
        activityService = new ActivityService(accounts, idempotentEvents,
                policyService, transitionEvents, clock);
        credentialService = new CredentialService(accounts, idempotentEvents,
                policyService, transitionEvents, clock);
        scanner = new LifecycleScanner(accounts, activityService, credentialService,
                policyService, transitionEvents, clock);
        recoveryService = new RecoveryService(eventLog, accounts, idempotentEvents,
                transitionEvents, policyService, objectMapper, clock);
        recoveryService.init();
        auditQueryService = new AuditQueryService(eventLog);
    }

    /** 模拟进程重启：丢弃所有内存状态，仅靠事件日志重建。 */
    public void restart() {
        boot();
    }

    public Instant now() {
        return clock.instant();
    }

    public void setTime(Instant at) {
        clock.setInstant(at);
    }

    public void advanceSeconds(long seconds) {
        clock.setInstant(clock.instant().plusSeconds(seconds));
    }

    public void advanceDays(long days) {
        advanceSeconds(days * 86_400);
    }

    public Account register(String id) {
        return accountService.register(id, PolicyService.DEFAULT_GROUP, null);
    }

    /** 上报一次发生在 at 前 1 秒的活动，使 at 时刻的扫描不会触发休眠判定。 */
    public void keepActiveAt(String id, Instant at) {
        activityService.report(id, at.minusSeconds(1), "keep-active-" + id + "-" + at);
    }

    public List<LifecycleEvent> audit(String accountId) {
        return auditQueryService.byAccount(accountId);
    }

    static class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
