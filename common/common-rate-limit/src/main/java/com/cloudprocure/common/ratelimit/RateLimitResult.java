package com.cloudprocure.common.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * <p>拒绝时 {@code retryAfterSeconds} 必须为正数，调用方可以直接写入 HTTP
 * {@code Retry-After} 头。{@code remaining} 是给日志/可观测性用的剩余额度，
 * 不参与安全判断；它可能是 0，但 {@code allowed=true} 时仍表示本次请求已获准。</p>
 *
 * @param allowed 是否允许本次请求
 * @param retryAfterSeconds 建议等待秒数；允许时为 0，拒绝时至少为 1
 * @param remaining 本次判定后窗口内剩余的整数额度（令牌桶/漏桶向下取整）
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds, long remaining) {

    public RateLimitResult {
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds 不能为负数");
        }
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining 不能为负数");
        }
        // 允许却要求等待、拒绝却不给等待时间，都会让调用方写出自相矛盾的响应。
        if (allowed && retryAfterSeconds != 0) {
            throw new IllegalArgumentException("允许的请求不能带 Retry-After");
        }
        if (!allowed && retryAfterSeconds == 0) {
            throw new IllegalArgumentException("拒绝的请求必须给出正数 Retry-After");
        }
    }

    /**
     * 兼容 issue 中两参数结果的构造方式；剩余额度未知时记为 0。
     */
    public RateLimitResult(boolean allowed, long retryAfterSeconds) {
        this(allowed, retryAfterSeconds, 0);
    }

    public static RateLimitResult allowed(long remaining) {
        return new RateLimitResult(true, 0, remaining);
    }

    public static RateLimitResult rejected(long retryAfterSeconds) {
        return new RateLimitResult(false, retryAfterSeconds, 0);
    }
}
