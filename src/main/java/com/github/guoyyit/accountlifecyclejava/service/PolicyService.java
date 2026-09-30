package com.github.guoyyit.accountlifecyclejava.service;

import com.github.guoyyit.accountlifecyclejava.config.LifecycleProperties;
import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.storage.IdempotentEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 分组策略服务。策略只追加版本，不覆盖历史；
 * 任意时刻按 effectiveFrom 解析当时生效的版本。
 */
@Service
public class PolicyService {

    public static final String DEFAULT_GROUP = "default";

    private final EventLog eventLog;
    private final IdempotentEvents idempotentEvents;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<LifecycleProperties> properties;
    private final Map<String, ConcurrentSkipListMap<Instant, GroupPolicy>> versions =
            new ConcurrentHashMap<>();
    private final Map<String, Integer> nextVersion = new ConcurrentHashMap<>();

    public PolicyService(EventLog eventLog, IdempotentEvents idempotentEvents, Clock clock,
                         ObjectMapper objectMapper,
                         ObjectProvider<LifecycleProperties> properties) {
        this.eventLog = eventLog;
        this.idempotentEvents = idempotentEvents;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        for (LifecycleEvent event : eventLog.readAll()) {
            if (event.type() == EventType.POLICY_CHANGED) {
                apply(event);
            }
        }
        if (!versions.containsKey(DEFAULT_GROUP)) {
            LifecycleProperties props = properties.getIfAvailable();
            GroupPolicy builtInDefault = new GroupPolicy(
                    DEFAULT_GROUP, 1, Instant.EPOCH,
                    props == null ? Duration.ofDays(90) : props.getDefaultDormantThreshold(),
                    props == null ? Duration.ofDays(365) : props.getDefaultCredentialValidity(),
                    props == null ? Duration.ofDays(7) : props.getDefaultGracePeriod(),
                    props == null ? Duration.ofDays(30) : props.getDefaultRecycleBuffer());
            store(builtInDefault, clock.instant());
        }
    }

    public GroupPolicy effectivePolicy(String groupName, Instant at) {
        String resolved = resolveGroup(groupName);
        ConcurrentSkipListMap<Instant, GroupPolicy> timeline = versions.get(resolved);
        Map.Entry<Instant, GroupPolicy> floor = timeline.floorEntry(at);
        return floor.getValue();
    }

    public GroupPolicy currentPolicy(String groupName) {
        return effectivePolicy(groupName, clock.instant());
    }

    public boolean hasGroup(String groupName) {
        return groupName != null && versions.containsKey(groupName);
    }

    public GroupPolicy updatePolicy(String groupName,
                                    Duration dormantThreshold,
                                    Duration credentialValidity,
                                    Duration gracePeriod,
                                    Duration recycleBuffer) {
        validatePositive("dormantThreshold", dormantThreshold);
        validatePositive("credentialValidity", credentialValidity);
        validatePositive("gracePeriod", gracePeriod);
        validatePositive("recycleBuffer", recycleBuffer);
        Instant now = clock.instant();
        int version = nextVersion.merge(groupName, 1, Integer::sum);
        GroupPolicy policy = new GroupPolicy(groupName, version, now,
                dormantThreshold, credentialValidity, gracePeriod, recycleBuffer);
        store(policy, now);
        return policy;
    }

    public List<GroupPolicy> versions(String groupName) {
        ConcurrentSkipListMap<Instant, GroupPolicy> timeline = versions.get(groupName);
        if (timeline == null) {
            return List.of();
        }
        return timeline.values().stream()
                .sorted(Comparator.comparingInt(GroupPolicy::version))
                .toList();
    }

    private String resolveGroup(String groupName) {
        if (groupName != null && versions.containsKey(groupName)) {
            return groupName;
        }
        return DEFAULT_GROUP;
    }

    private void validatePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须是正数: " + value);
        }
    }

    private void store(GroupPolicy policy, Instant now) {
        String detail;
        try {
            detail = objectMapper.writeValueAsString(policy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LifecycleEvent event = new LifecycleEvent(
                UUID.randomUUID().toString(),
                EventType.POLICY_CHANGED,
                "policy:" + policy.groupName(),
                now,
                null, null, null, detail, null);
        idempotentEvents.append(event);
        apply(event);
    }

    private void apply(LifecycleEvent event) {
        String groupName = event.accountId().substring("policy:".length());
        try {
            JsonNode node = objectMapper.readTree(event.detail());
            GroupPolicy policy = new GroupPolicy(
                    groupName,
                    node.get("version").asInt(),
                    Instant.parse(node.get("effectiveFrom").asText()),
                    Duration.parse(node.get("dormantThreshold").asText()),
                    Duration.parse(node.get("credentialValidity").asText()),
                    Duration.parse(node.get("gracePeriod").asText()),
                    Duration.parse(node.get("recycleBuffer").asText()));
            versions.computeIfAbsent(groupName,
                            key -> new ConcurrentSkipListMap<>())
                    .put(policy.effectiveFrom(), policy);
            nextVersion.put(groupName,
                    Math.max(nextVersion.getOrDefault(groupName, 1), policy.version()));
        } catch (IOException e) {
            throw new UncheckedIOException("重放策略事件失败: " + event.eventId(), e);
        }
    }
}
