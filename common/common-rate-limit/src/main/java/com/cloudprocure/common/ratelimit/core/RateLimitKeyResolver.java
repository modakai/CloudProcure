package com.cloudprocure.common.ratelimit.core;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitKey;

/**
 * 把业务限流键转换为 Redis 键。
 *
 * <p>把它做成接口而不是写死在 {@code RedisRateLimiter} 里，是为了让应用可以替换键布局
 * （例如接入统一的租户前缀）。自定义实现声明为 Spring Bean 后，自动配置会退让。</p>
 */
public interface RateLimitKeyResolver {

    /**
     * @param key 业务键，identifier 为原始值
     * @param algorithm 具体算法；实现必须拒绝 {@link RateLimitAlgorithm#DEFAULT}
     * @return 可直接用于 Redis 的完整键
     */
    String resolve(RateLimitKey key, RateLimitAlgorithm algorithm);

    /**
     * 日志/诊断用的固定前缀；自定义实现可以返回 {@code <custom>}。
     */
    default String basePrefix() {
        return "<custom>";
    }
}
