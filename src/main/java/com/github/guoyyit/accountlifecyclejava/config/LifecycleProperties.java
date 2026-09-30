package com.github.guoyyit.accountlifecyclejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "account-lifecycle")
public class LifecycleProperties {

    private boolean enabled = true;
    private String dataDir = "data/account-lifecycle";
    private Duration scanInterval = Duration.ofMinutes(1);
    private boolean schedulerEnabled = false;
    private Duration defaultDormancyThreshold = Duration.ofDays(90);
    private Duration defaultCredentialGracePeriod = Duration.ofDays(7);
    private Duration defaultDormantBufferPeriod = Duration.ofDays(30);
    private Duration defaultAccountMaxLifetime = Duration.ZERO;
    private Duration defaultRecoveryPendingTtl = Duration.ofHours(24);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Duration getScanInterval() {
        return scanInterval;
    }

    public void setScanInterval(Duration scanInterval) {
        this.scanInterval = scanInterval;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public Duration getDefaultDormancyThreshold() {
        return defaultDormancyThreshold;
    }

    public void setDefaultDormancyThreshold(Duration defaultDormancyThreshold) {
        this.defaultDormancyThreshold = defaultDormancyThreshold;
    }

    public Duration getDefaultCredentialGracePeriod() {
        return defaultCredentialGracePeriod;
    }

    public void setDefaultCredentialGracePeriod(Duration defaultCredentialGracePeriod) {
        this.defaultCredentialGracePeriod = defaultCredentialGracePeriod;
    }

    public Duration getDefaultDormantBufferPeriod() {
        return defaultDormantBufferPeriod;
    }

    public void setDefaultDormantBufferPeriod(Duration defaultDormantBufferPeriod) {
        this.defaultDormantBufferPeriod = defaultDormantBufferPeriod;
    }

    public Duration getDefaultAccountMaxLifetime() {
        return defaultAccountMaxLifetime;
    }

    public void setDefaultAccountMaxLifetime(Duration defaultAccountMaxLifetime) {
        this.defaultAccountMaxLifetime = defaultAccountMaxLifetime;
    }

    public Duration getDefaultRecoveryPendingTtl() {
        return defaultRecoveryPendingTtl;
    }

    public void setDefaultRecoveryPendingTtl(Duration defaultRecoveryPendingTtl) {
        this.defaultRecoveryPendingTtl = defaultRecoveryPendingTtl;
    }
}
