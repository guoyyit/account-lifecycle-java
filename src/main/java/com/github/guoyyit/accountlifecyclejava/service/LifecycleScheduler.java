package com.github.guoyyit.accountlifecyclejava.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进程内调度器。单线程周期触发扫描，扫描本身并发安全；
 * 重启后由事件重放恢复状态，错过的扫描在下次触发或手动触发时幂等补做。
 */
public class LifecycleScheduler {

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "lifecycle-scanner");
                thread.setDaemon(true);
                return thread;
            });

    private final LifecycleScanner scanner;
    private final Clock clock;
    private final boolean enabled;
    private final java.time.Duration interval;

    public LifecycleScheduler(LifecycleScanner scanner,
                              Clock clock,
                              boolean enabled,
                              Duration interval) {
        this.scanner = scanner;
        this.clock = clock;
        this.enabled = enabled;
        this.interval = interval;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        // 初始延迟与周期相同；重启后第一次扫描会幂等补做停机期间到期的所有状态迁移。
        executor.scheduleAtFixedRate(this::safeScan,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private void safeScan() {
        try {
            scanner.scanOnce(clock.instant());
        } catch (RuntimeException e) {
            // 扫描异常不杀死调度线程；账号级迁移已保证幂等，下一周期继续。
            System.getLogger(LifecycleScheduler.class.getName())
                    .log(System.Logger.Level.WARNING, "周期扫描失败，将在下个周期重试", e);
        }
    }
}
