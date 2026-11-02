package com.cloudprocure.common.ratelimit;

import java.time.Duration;

/**
 * 程序化限流入口。
 *
 * <p>它不依赖 Spring MVC、Security 或 AOP，专供 Servlet {@code Filter}、Security 过滤器链、
 * 消息消费者等无法被 Spring 代理拦截的入口调用。注解 {@code @RateLimit} 最终也走同一接口，
 * 避免 AOP 与程序化调用出现两套语义。</p>
 *
 * <p><b>失败契约</b>：实现不得在 Redis 故障时返回 allowed，也不得静默回退内存。
 * 连接/超时/认证/脚本错误必须抛 {@link RateLimitUnavailableException}；额度耗尽返回
 * {@code allowed=false} 且 {@code retryAfterSeconds>=1}。</p>
 */
public interface RateLimiter {

    /**
     * 使用配置中的默认算法获取一个额度。
     *
     * <p>这是 issue 中约定的三参数入口，适合不希望把算法写死在业务代码里的调用方。</p>
     */
    default RateLimitResult tryAcquire(RateLimitKey key, int limit, Duration window) {
        return tryAcquire(key, limit, window, RateLimitAlgorithm.DEFAULT);
    }

    /**
     * 指定算法获取一个额度。
     *
     * @param key 业务键；标识会在存储层摘要化
     * @param limit 窗口/桶容量内的最大请求数，必须为正
     * @param window 窗口长度或桶的完整补充/泄漏周期，必须为正
     * @param algorithm 算法；{@link RateLimitAlgorithm#DEFAULT} 表示使用全局默认
     */
    RateLimitResult tryAcquire(RateLimitKey key, int limit, Duration window,
                               RateLimitAlgorithm algorithm);
}
