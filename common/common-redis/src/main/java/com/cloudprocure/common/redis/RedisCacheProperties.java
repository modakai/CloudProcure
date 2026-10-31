package com.cloudprocure.common.redis;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * common-redis 缓存约定。
 *
 * <p>默认 TTL 不是可有可无的优化项：RedisCache 在 TTL 为零时写入永不过期的键，
 * 某个缓存名一旦漏配就会长期保留旧数据。因此这里给出有界默认值，并拒绝零/负 TTL。</p>
 *
 * <p>键前缀默认由 {@code spring.application.name} 生成；多个环境共用一个 Redis 时，
 * 必须显式配置包含环境标识的 {@code key-prefix}，否则不同环境会读到彼此的缓存。</p>
 */
@ConfigurationProperties(prefix = "cloudprocure.redis.cache")
public record RedisCacheProperties(
        String keyPrefix,
        @DefaultValue("10m") Duration defaultTtl,
        @DefaultValue("1m") Duration ttlJitter,
        @DefaultValue("false") boolean cacheNullValues,
        Map<String, CacheSpec> caches) implements InitializingBean {

    public RedisCacheProperties {
        // 未配置时 Map 绑定结果为 null；统一为不可变空映射，避免调用方到处判空。
        caches = caches == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(caches));
    }

    @Override
    public void afterPropertiesSet() {
        requirePositive("cloudprocure.redis.cache.default-ttl", defaultTtl);
        requireJitter(ttlJitter);
        caches.forEach(this::validateCache);
    }

    private void validateCache(String cacheName, CacheSpec spec) {
        if (!StringUtils.hasText(cacheName)) {
            throw new IllegalArgumentException("cloudprocure.redis.cache.caches 的缓存名不能为空");
        }
        // 缓存名会进入 SCAN MATCH 模式；带通配符时一次清理可能波及其他缓存。
        requireNoGlob("cloudprocure.redis.cache.caches." + cacheName, cacheName);
        if (spec == null || spec.ttl() == null) {
            throw new IllegalArgumentException(
                    "cloudprocure.redis.cache.caches." + cacheName + ".ttl 必须显式配置");
        }
        requirePositive("cloudprocure.redis.cache.caches." + cacheName + ".ttl", spec.ttl());
    }

    private static void requirePositive(String property, Duration value) {
        // Redis 过期精度是毫秒；不足 1ms 的正时长会被截断为 0，最终写成永久键。
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(property + " 必须是至少 1ms 的正时长");
        }
    }

    private static void requireJitter(Duration jitter) {
        if (jitter == null || jitter.isNegative()
                || (!jitter.isZero() && jitter.toMillis() <= 0)) {
            throw new IllegalArgumentException(
                    "cloudprocure.redis.cache.ttl-jitter 必须为 0 或至少 1ms 的非负时长");
        }
    }

    private static void requireNoGlob(String property, String value) {
        if (value.chars().anyMatch(ch -> ch == '*' || ch == '?' || ch == '[' || ch == ']')) {
            throw new IllegalArgumentException(
                    property + " 不能包含 Redis 通配符 * ? [ ]");
        }
    }

    /** 单个缓存的过期覆盖；未列出的缓存使用 {@link #defaultTtl()}。 */
    public record CacheSpec(Duration ttl) {
    }
}
