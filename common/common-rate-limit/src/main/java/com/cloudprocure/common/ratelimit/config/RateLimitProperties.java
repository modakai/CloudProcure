package com.cloudprocure.common.ratelimit.config;

import java.time.Duration;
import java.util.Locale;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitConfigurationException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * {@code cloudprocure.rate-limit} 配置。
 *
 * <p>这里刻意把“默认额度/窗口”和“单次调用额度/窗口”分开：注解与程序化 API 可以覆盖单次值，
 * 但默认值必须是有界、可启动校验的，否则漏配的注解会变成无限流或超长 TTL。</p>
 *
 * <p>所有时长至少 1ms：Redis 的 {@code PX}/{@code PEXPIRE} 精度是毫秒，不足 1ms 的正时长
 * 会被截断为 0，最终写出没有过期时间的键。</p>
 */
@ConfigurationProperties(prefix = "cloudprocure.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        String keyPrefix,
        String environment,
        String application,
        @DefaultValue("100") int defaultLimit,
        @DefaultValue("1m") Duration defaultWindow,
        @DefaultValue("FIXED_WINDOW") RateLimitAlgorithm defaultAlgorithm,
        @DefaultValue("100000") int maxLimit,
        @DefaultValue("1d") Duration maxWindow,
        @DefaultValue("512") int maxKeyLength,
        @DefaultValue("512") int maxIdentifierLength,
        @DefaultValue("true") boolean logDecisions,
        @DefaultValue("-2147483548") int aopOrder) implements InitializingBean {

    /** 公共键前缀的最大长度；超过后通常意味着配置里复制了错误的长字符串。 */
    private static final int MAX_PREFIX_LENGTH = 256;

    public RateLimitProperties {
        // 绑定空字符串时统一成 null，后续“是否显式配置”的判断只需看 null。
        keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : null;
        environment = StringUtils.hasText(environment) ? environment.trim() : null;
        application = StringUtils.hasText(application) ? application.trim() : null;
    }

    /**
     * 测试与文档用默认配置。
     *
     * <p>生产环境由 Spring Boot 绑定，不调用这个工厂；它只是避免测试里反复抄 13 个参数。</p>
     */
    public static RateLimitProperties defaults() {
        return new RateLimitProperties(true, null, null, null, 100, Duration.ofMinutes(1),
                RateLimitAlgorithm.FIXED_WINDOW, 100_000, Duration.ofDays(1), 512, 512,
                true, Integer.MIN_VALUE + 100);
    }

    @Override
    public void afterPropertiesSet() {
        requirePositive("default-window", defaultWindow);
        requirePositive("max-window", maxWindow);
        if (maxWindow.compareTo(defaultWindow) < 0) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.max-window 不能小于 default-window");
        }
        if (maxLimit < 1) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.max-limit 必须为正数");
        }
        if (defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.default-limit 必须在 1..max-limit 之间");
        }
        if (defaultAlgorithm == null || !defaultAlgorithm.isConcrete()) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.default-algorithm 必须是具体算法，不能是 DEFAULT");
        }
        if (maxKeyLength < 64 || maxKeyLength > 2048) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.max-key-length 必须在 64..2048 之间");
        }
        if (maxIdentifierLength < 1 || maxIdentifierLength > 4096) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.max-identifier-length 必须在 1..4096 之间");
        }
        if (keyPrefix != null) {
            requireKeyPrefix(keyPrefix);
        }
        if (environment != null) {
            requireNameSegment("environment", environment);
        }
        if (application != null) {
            requireNameSegment("application", application);
        }
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit." + property + " 必须是至少 1ms 的正时长");
        }
        try {
            if (value.toMillis() <= 0) {
                throw new RateLimitConfigurationException(
                        "cloudprocure.rate-limit." + property + " 必须是至少 1ms 的正时长");
            }
        } catch (ArithmeticException exception) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit." + property + " 数值过大，无法转换为毫秒", exception);
        }
    }

    private static void requireKeyPrefix(String prefix) {
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit.key-prefix 长度不能超过 " + MAX_PREFIX_LENGTH);
        }
        for (int index = 0; index < prefix.length(); index++) {
            char ch = prefix.charAt(index);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)
                    || "*?[]".indexOf(ch) >= 0) {
                throw new RateLimitConfigurationException(
                        "cloudprocure.rate-limit.key-prefix 不能包含空白、控制字符或通配符 * ? [ ]");
            }
        }
    }

    /**
     * 环境和应用名会直接进入 Redis 键，限制为常见字符集，避免配置错误产生歧义键。
     */
    private static void requireNameSegment(String property, String value) {
        if (value.length() > 128) {
            throw new RateLimitConfigurationException(
                    "cloudprocure.rate-limit." + property + " 长度不能超过 128");
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)
                    || "*?[]:".indexOf(ch) >= 0
                    || (!Character.isLetterOrDigit(ch) && "._-".indexOf(ch) < 0)) {
                throw new RateLimitConfigurationException(
                        "cloudprocure.rate-limit." + property
                                + " 只能包含字母、数字、点、下划线和短横线");
            }
        }
    }

    /** 便于日志输出算法名；枚举转换后已经是受控值，不需要再校验。 */
    public String defaultAlgorithmName() {
        return defaultAlgorithm.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
