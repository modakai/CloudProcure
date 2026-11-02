package com.cloudprocure.common.ratelimit.core;

import java.time.Duration;
import java.util.List;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitConfigurationException;
import com.cloudprocure.common.ratelimit.RateLimitKey;
import com.cloudprocure.common.ratelimit.RateLimitResult;
import com.cloudprocure.common.ratelimit.RateLimitUnavailableException;
import com.cloudprocure.common.ratelimit.RateLimiter;
import com.cloudprocure.common.ratelimit.config.RateLimitProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.ScriptingException;

/**
 * 基于 Redis 单键 Lua 的限流实现。
 *
 * <p>所有算法都只用一把键，并在一个 Lua 脚本里完成“读取状态 + 判定 + 写回”。
 * Redis 执行 Lua 期间不会插入其他命令，因此多个应用实例看到的是同一份原子状态；
 * 这比先 GET 再 INCR 的两条命令可靠。跨维度（例如账号 + 来源）是两次独立脚本调用，
 * 不是跨键事务，允许保守多消耗。</p>
 *
 * <p>Redis 故障、超时、脚本错误、返回值异常统一转换为
 * {@link RateLimitUnavailableException}。这里不捕获后放行，也不做内存降级：限流器
 * 无法确认额度时必须让调用方失败，而不是让攻击者绕过保护。</p>
 */
public final class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate template;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitProperties properties;
    private final RateLimitDecisionLogger decisionLogger;

    public RedisRateLimiter(StringRedisTemplate template, RateLimitKeyResolver keyResolver,
                            RateLimitProperties properties,
                            RateLimitDecisionLogger decisionLogger) {
        if (template == null || keyResolver == null || properties == null || decisionLogger == null) {
            throw new RateLimitConfigurationException("RedisRateLimiter 的依赖不能为 null");
        }
        this.template = template;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.decisionLogger = decisionLogger;
        // 触发脚本枚举初始化：classpath 中缺少某个 Lua 时，应用启动就失败，
        // 而不是等第一个请求到达才发现 503。
        RateLimitScript.values();
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitKey key, int limit, Duration window,
                                      RateLimitAlgorithm algorithm) {
        RateLimitAlgorithm effectiveAlgorithm = resolveAlgorithm(algorithm);
        validateLimit(limit);
        long windowMillis = validateWindow(window);
        String redisKey = keyResolver.resolve(key, effectiveAlgorithm);

        long startedAt = System.nanoTime();
        List<?> rawResult;
        try {
            // 所有参数都显式转成字符串：StringRedisTemplate 的序列化器不会把 Integer/Long
            // 当数字处理，直接传对象会得到不可预期的字节序列。
            rawResult = template.execute(RateLimitScript.forAlgorithm(effectiveAlgorithm).script(),
                    List.of(redisKey), Integer.toString(limit), Long.toString(windowMillis));
        } catch (DataAccessException | ScriptingException exception) {
            // 连接失败、认证失败、超时、WRONGTYPE、Lua error 都在这里收敛为同一种 fail-closed 语义。
            decisionLogger.unavailable(key, effectiveAlgorithm, exception);
            throw new RateLimitUnavailableException("限流存储不可用，已按 fail-closed 拒绝请求", exception);
        }

        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000;
        RateLimitResult result = parseResult(rawResult, limit);
        if (result.allowed()) {
            decisionLogger.allowed(key, effectiveAlgorithm, limit, window, result.remaining(),
                    elapsedMicros);
        } else {
            decisionLogger.rejected(key, effectiveAlgorithm, limit, window,
                    result.retryAfterSeconds(), elapsedMicros);
        }
        return result;
    }

    /**
     * DEFAULT 必须在入口解析：如果把 DEFAULT 传进脚本映射，枚举会直接抛异常，
     * 但那属于实现缺陷而不是配置错误，错误信息不直观。
     */
    private RateLimitAlgorithm resolveAlgorithm(RateLimitAlgorithm algorithm) {
        if (algorithm == null) {
            throw new RateLimitConfigurationException("限流算法不能为 null");
        }
        RateLimitAlgorithm effective = algorithm == RateLimitAlgorithm.DEFAULT
                ? properties.defaultAlgorithm() : algorithm;
        if (effective == null || !effective.isConcrete()) {
            throw new RateLimitConfigurationException("默认限流算法必须是具体算法");
        }
        return effective;
    }

    private void validateLimit(int limit) {
        // 上限不是业务规则，而是对滑动窗口内存和单键 TTL 的保护；超过上限说明调用方配错。
        if (limit < 1 || limit > properties.maxLimit()) {
            throw new RateLimitConfigurationException(
                    "limit 必须在 1.." + properties.maxLimit() + " 之间");
        }
    }

    private long validateWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new RateLimitConfigurationException("window 必须是至少 1ms 的正时长");
        }
        // 先比较上限再转毫秒：超大 Duration 的 toMillis() 会溢出，不能把它暴露成
        // ArithmeticException 或截断成 0 后写出永久键。
        if (window.compareTo(properties.maxWindow()) > 0) {
            throw new RateLimitConfigurationException(
                    "window 不能超过配置上限 " + properties.maxWindow());
        }
        long millis;
        try {
            millis = window.toMillis();
        } catch (ArithmeticException exception) {
            throw new RateLimitConfigurationException("window 数值过大，无法转换为毫秒", exception);
        }
        if (millis <= 0) {
            throw new RateLimitConfigurationException("window 必须是至少 1ms 的正时长");
        }
        return millis;
    }

    /**
     * Lua 返回三元素数组。任何长度、类型、状态码异常都不能猜测成“允许”，
     * 只能按存储状态损坏处理。
     */
    private RateLimitResult parseResult(List<?> rawResult, int limit) {
        if (rawResult == null || rawResult.size() < 3) {
            throw new RateLimitUnavailableException("限流脚本返回值格式不正确");
        }
        long allowed = toLong(rawResult.get(0), "allowed");
        long retryAfterMillis = toLong(rawResult.get(1), "retryAfterMillis");
        long remaining = toLong(rawResult.get(2), "remaining");

        if (allowed != 0 && allowed != 1) {
            throw new RateLimitUnavailableException("限流脚本返回了未知的 allowed 状态");
        }
        if (retryAfterMillis < 0 || remaining < 0 || remaining > limit) {
            throw new RateLimitUnavailableException("限流脚本返回了非法的时间/剩余额度");
        }
        if (allowed == 1) {
            return RateLimitResult.allowed(remaining);
        }
        // Redis PTTL 可能返回 0（即将过期）；对外仍必须给出正数 Retry-After。
        long retryAfterSeconds = Math.max(1, Math.ceilDiv(retryAfterMillis, 1000L));
        return RateLimitResult.rejected(retryAfterSeconds);
    }

    private static long toLong(Object value, String field) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new RateLimitUnavailableException("限流脚本返回值 " + field + " 不是数字");
    }
}
