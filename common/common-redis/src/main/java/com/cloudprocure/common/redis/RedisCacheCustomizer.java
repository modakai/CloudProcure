package com.cloudprocure.common.redis;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 把公共缓存约定应用到 Boot 创建的 RedisCacheManager。
 *
 * <p>刻意不自己声明 CacheManager：只有应用真正启用缓存、且 Boot 选中 Redis 时才接管，
 * 这样应用同时存在 Caffeine 等实现时，仍由 {@code spring.cache.type} 决定。</p>
 *
 * <p>这里从 {@link RedisCacheConfiguration#defaultCacheConfig()} 重新构建，而不是在
 * Boot 的默认值上修改，避免 Boot 默认的 JDK 值序列化器和另一套 {@code spring.cache.redis.*}
 * 配置与本模块的约定混用。</p>
 */
final class RedisCacheCustomizer implements RedisCacheManagerBuilderCustomizer {

    private final RedisCacheProperties properties;
    private final RedisSerializer<Object> valueSerializer;
    private final Environment environment;

    RedisCacheCustomizer(RedisCacheProperties properties,
            RedisSerializer<Object> valueSerializer, Environment environment) {
        this.properties = properties;
        this.valueSerializer = valueSerializer;
        this.environment = environment;
    }

    @Override
    public void customize(RedisCacheManager.RedisCacheManagerBuilder builder) {
        String keyPrefix = RedisAutoConfiguration.resolveKeyPrefix(properties, environment);
        RedisCacheConfiguration configured = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(SerializationPair.fromSerializer(valueSerializer))
                // 前缀包含应用名，多个服务共用同一 Redis 时不会互相覆盖同名缓存。
                .prefixCacheNameWith(keyPrefix)
                .entryTtl(ttlFunction(properties.defaultTtl(), properties.ttlJitter()));

        RedisCacheConfiguration defaults = properties.cacheNullValues()
                ? configured
                : configured.disableCachingNullValues();

        builder.cacheDefaults(defaults);

        // 未在配置中列出的缓存仍使用默认 TTL，不会因为新增 @Cacheable 而变成永久缓存。
        properties.caches().forEach((cacheName, spec) -> builder.withCacheConfiguration(
                cacheName,
                defaults.entryTtl(ttlFunction(spec.ttl(), properties.ttlJitter()))));
    }

    /**
     * 写入时随机延长 TTL，降低同一批缓存同时过期、同时回源造成的尖峰。
     *
     * <p>抖动只解决“同时过期”，不能替代 {@code @Cacheable(sync = true)} 对单键并发的保护。</p>
     */
    static TtlFunction ttlFunction(Duration ttl, Duration jitter) {
        long baseMillis = ttl.toMillis();
        long jitterMillis = jitter.toMillis();
        if (jitterMillis == 0) {
            return TtlFunction.just(ttl);
        }
        return (key, value) -> Duration.ofMillis(
                Math.addExact(baseMillis, ThreadLocalRandom.current().nextLong(jitterMillis + 1)));
    }
}
