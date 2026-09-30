package com.github.guoyyit.accountlifecyclejava.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 分组策略的一个不可变版本。策略变更产生新版本，只影响变更之后的判定。
 *
 * @param dormantThreshold   多久无活动进入休眠
 * @param credentialValidity 新凭据的有效期
 * @param gracePeriod        凭据过期后的宽限期
 * @param recycleBuffer      休眠账号回收前的缓冲期
 */
public record GroupPolicy(
        String groupName,
        int version,
        Instant effectiveFrom,
        Duration dormantThreshold,
        Duration credentialValidity,
        Duration gracePeriod,
        Duration recycleBuffer
) {
}
