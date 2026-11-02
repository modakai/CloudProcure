package com.cloudprocure.common.ratelimit;

/**
 * 限流算法类型。
 *
 * <p>算法不是可以随意互换的实现细节：不同算法消耗的 Redis 数据结构不同，允许的突发形态也不同。
 * 因此算法名会进入 Redis 键（见 {@link #keySegment()}），避免同一个键上从 String 计数
 * 切到 ZSET/HSET 时触发 WRONGTYPE，或把旧窗口的数据误读成新算法的状态。</p>
 *
 * <p>{@link #DEFAULT} 只是配置/注解的占位值，表示“使用
 * {@code cloudprocure.rate-limit.default-algorithm}”，不得直接用于执行脚本。</p>
 */
public enum RateLimitAlgorithm {

    /** 使用全局默认算法；只有注解和程序化 API 的便捷重载会传入。 */
    DEFAULT,

    /**
     * 固定窗口：从首次获准开始计时，窗口内累计请求数，超过额度即拒绝。
     *
     * <p>实现最简单、Redis 只存一个整数；代价是窗口边界可能出现最多两倍额度的突发。</p>
     */
    FIXED_WINDOW,

    /**
     * 滑动窗口：用 ZSET 记录每次获准的时间，按“当前时刻往前一个窗口”精确统计。
     *
     * <p>没有固定窗口的边界突发，但每个键最多保留 {@code limit} 个成员，内存随额度增长。</p>
     */
    SLIDING_WINDOW,

    /**
     * 令牌桶：桶内最多存 {@code limit} 个令牌，按 {@code limit/window} 的速率补充；
     * 每个请求取走一个令牌，取不到就拒绝。
     *
     * <p>允许额度范围内的突发，同时限制长期平均速率。</p>
     */
    TOKEN_BUCKET,

    /**
     * 漏桶：请求进入容量为 {@code limit} 的“桶”，桶按 {@code limit/window} 的速率泄漏；
     * 桶满时拒绝。
     *
     * <p>本模块只做准入判定，不把请求排队延迟执行；因此它是漏桶计量器，不是流量整形调度器。</p>
     */
    LEAKY_BUCKET;

    /**
     * Redis 键中的算法段。
     *
     * <p>用 kebab-case 而不是枚举名，是为了让键可读；同时该值在升级时必须保持稳定，
     * 否则同一业务限流会在发布后重新获得完整额度。</p>
     */
    public String keySegment() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** 是否为可执行的具体算法（排除 {@link #DEFAULT}）。 */
    public boolean isConcrete() {
        return this != DEFAULT;
    }
}
