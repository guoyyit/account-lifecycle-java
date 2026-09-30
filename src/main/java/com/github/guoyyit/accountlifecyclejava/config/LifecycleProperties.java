package com.github.guoyyit.accountlifecyclejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 全部配置项见 README：account-lifecycle.* 前缀。
 */
@ConfigurationProperties(prefix = "account-lifecycle")
public class LifecycleProperties {

    private Path dataDir = Path.of("data", "lifecycle");

    private boolean schedulerEnabled = true;

    private Duration scanInterval = Duration.ofSeconds(30);

    private Duration defaultDormantThreshold = Duration.ofDays(90);

    private Duration defaultCredentialValidity = Duration.ofDays(365);

    private Duration defaultGracePeriod = Duration.ofDays(7);

    private Duration defaultRecycleBuffer = Duration.ofDays(30);

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public Duration getScanInterval() {
        return scanInterval;
    }

    public void setScanInterval(Duration scanInterval) {
        this.scanInterval = scanInterval;
    }

    public Duration getDefaultDormantThreshold() {
        return defaultDormantThreshold;
    }

    public void setDefaultDormantThreshold(Duration defaultDormantThreshold) {
        this.defaultDormantThreshold = defaultDormantThreshold;
    }

    public Duration getDefaultCredentialValidity() {
        return defaultCredentialValidity;
    }

    public void setDefaultCredentialValidity(Duration defaultCredentialValidity) {
        this.defaultCredentialValidity = defaultCredentialValidity;
    }

    public Duration getDefaultGracePeriod() {
        return defaultGracePeriod;
    }

    public void setDefaultGracePeriod(Duration defaultGracePeriod) {
        this.defaultGracePeriod = defaultGracePeriod;
    }

    public Duration getDefaultRecycleBuffer() {
        return defaultRecycleBuffer;
    }

    public void setDefaultRecycleBuffer(Duration defaultRecycleBuffer) {
        this.defaultRecycleBuffer = defaultRecycleBuffer;
    }
}
