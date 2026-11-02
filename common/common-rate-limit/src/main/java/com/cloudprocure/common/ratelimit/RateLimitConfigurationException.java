package com.cloudprocure.common.ratelimit;

/**
 * 限流配置/调用参数错误。
 *
 * <p>它表示程序缺陷或配置错误，不是“超过额度”，也不是“Redis 不可用”。调用方不应把
 * 它映射成 429；更不应该捕获后放行，否则一个写错的 SpEL/key 就会变成无限流。</p>
 */
public class RateLimitConfigurationException extends IllegalArgumentException {

    public RateLimitConfigurationException(String message) {
        super(message);
    }

    public RateLimitConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
