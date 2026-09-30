package com.github.guoyyit.accountlifecyclejava.config;

import com.github.guoyyit.accountlifecyclejava.engine.ScanCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "account-lifecycle", name = "scheduler-enabled", havingValue = "true")
public class LifecycleScheduler {

    private final ScanCoordinator scanCoordinator;

    public LifecycleScheduler(ScanCoordinator scanCoordinator) {
        this.scanCoordinator = scanCoordinator;
    }

    @Scheduled(fixedDelayString = "${account-lifecycle.scan-interval:PT1M}")
    public void scheduledScan() {
        scanCoordinator.scanOnce();
    }
}
