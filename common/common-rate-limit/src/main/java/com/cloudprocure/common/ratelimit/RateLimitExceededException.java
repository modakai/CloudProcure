package com.cloudprocure.common.ratelimit;

/**
 * 请求超过限流额度。
 *
 * <p>只携带 {@code Retry-After} 秒数，不携带用户名、来源、Redis 键或摘要；调用方可以安全地
 * 把它映射成 429，而不必担心异常消息泄露账号是否存在。</p>
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(createMessage(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static String createMessage(long retryAfterSeconds) {
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds 必须为正数");
        }
        return "请求超过限流额度，请 " + retryAfterSeconds + " 秒后重试";
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
