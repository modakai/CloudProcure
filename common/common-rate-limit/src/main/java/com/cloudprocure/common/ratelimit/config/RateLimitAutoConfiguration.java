package com.cloudprocure.common.ratelimit.config;

import com.cloudprocure.common.ratelimit.RateLimiter;
import com.cloudprocure.common.ratelimit.aop.RateLimitAspect;
import com.cloudprocure.common.ratelimit.core.DefaultRateLimitKeyResolver;
import com.cloudprocure.common.ratelimit.core.RateLimitDecisionLogger;
import com.cloudprocure.common.ratelimit.core.RateLimitKeyResolver;
import com.cloudprocure.common.ratelimit.core.RedisRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * common-rate-limit 自动配置。
 *
 * <p>排在 Boot 的 Redis 自动配置之后，因为 {@link StringRedisTemplate} 由后者提供。
 * 只要启用（默认 true），就要求 Redis 模板存在；缺失时启动失败而不是静默地“没有限流”。
 * 这是配置层面的 fail-closed：依赖限流保护的应用不应该在漏配 Redis 后继续裸奔。</p>
 *
 * <p>退让点：应用自定义 {@link RateLimiter} 时默认 Redis 限流器不创建；自定义
 * {@link RateLimitKeyResolver} 时默认解析器不被使用；自定义 {@link RateLimitDecisionLogger}
 * 时默认日志器不创建。完全关闭本模块使用
 * {@code cloudprocure.rate-limit.enabled=false}，但那只适合确实不需要限流的应用。</p>
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnClass({StringRedisTemplate.class, RateLimiter.class})
@ConditionalOnProperty(prefix = "cloudprocure.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RateLimitDecisionLogger rateLimitDecisionLogger(RateLimitProperties properties) {
        return new RateLimitDecisionLogger(properties);
    }

    /**
     * 默认解析器在限流器 Bean 内部按需创建，而不是单独注册 Bean。
     *
     * <p>这样应用自定义 {@link RateLimiter} 时，自动配置不会因为“默认解析器需要
     * spring.application.name”而提前失败——自定义限流器可能根本不需要 Redis 键前缀。
     * 应用仍可声明自己的 {@link RateLimitKeyResolver} Bean，通过 {@link ObjectProvider}
     * 注入并被优先使用。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    RateLimiter rateLimiter(RateLimitProperties properties,
                            ObjectProvider<RateLimitKeyResolver> resolverProvider,
                            ObjectProvider<StringRedisTemplate> templateProvider,
                            RateLimitDecisionLogger decisionLogger,
                            Environment environment) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("common-rate-limit 已启用但找不到 StringRedisTemplate。"
                    + "请配置 spring.data.redis.*，或显式设置 cloudprocure.rate-limit.enabled=false");
        }
        RateLimitKeyResolver keyResolver = resolverProvider.getIfAvailable(
                () -> new DefaultRateLimitKeyResolver(properties, environment));
        RateLimiter limiter = new RedisRateLimiter(template, keyResolver, properties, decisionLogger);
        decisionLogger.startup(keyResolver.basePrefix(), properties);
        return limiter;
    }

    /**
     * AOP 单独成组：即使某个应用排除了 AspectJ，程序化 {@link RateLimiter} 仍然可用。
     * 用字符串条件而不是类字面量，避免 AspectJ 缺失时加载本配置的方法签名失败。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    static class AspectConfiguration {

        @Bean
        @ConditionalOnMissingBean
        RateLimitAspect rateLimitAspect(RateLimiter rateLimiter, RateLimitProperties properties) {
            return new RateLimitAspect(rateLimiter, properties);
        }
    }
}
