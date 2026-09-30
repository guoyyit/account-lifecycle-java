package com.github.guoyyit.accountlifecyclejava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.accountlifecyclejava.storage.FileEventLog;
import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.service.LifecycleScheduler;
import com.github.guoyyit.accountlifecyclejava.service.LifecycleScanner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public EventLog eventLog(LifecycleProperties properties, ObjectMapper objectMapper) {
        ObjectMapper logMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        return new FileEventLog(
                properties.getDataDir().resolve("lifecycle-events.log"), logMapper);
    }

    @Bean
    public LifecycleScheduler lifecycleScheduler(LifecycleScanner scanner,
                                                 Clock clock,
                                                 LifecycleProperties properties) {
        return new LifecycleScheduler(scanner, clock,
                properties.isSchedulerEnabled(), properties.getScanInterval());
    }
}
