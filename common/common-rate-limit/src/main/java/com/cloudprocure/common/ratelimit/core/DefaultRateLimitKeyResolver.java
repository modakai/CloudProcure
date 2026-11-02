package com.cloudprocure.common.ratelimit.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitConfigurationException;
import com.cloudprocure.common.ratelimit.RateLimitKey;
import com.cloudprocure.common.ratelimit.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 默认 Redis 键布局：
 *
 * <pre>
 * cp:&lt;environment&gt;:&lt;application&gt;:rate-limit:v1:&lt;namespace&gt;:&lt;dimension&gt;:&lt;algorithm&gt;:&lt;sha256(identifier)&gt;
 * </pre>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>环境/应用段防止共用 Redis 的不同服务互相扣额度；显式 {@code key-prefix} 可以整体覆盖。</li>
 *   <li>算法段防止切换算法时把 String 计数当 ZSET/HASH 读取，触发 WRONGTYPE 或把旧状态
 *       误解释成新算法。代价是切换算法会重新获得完整额度，发布时必须接受这一点。</li>
 *   <li>identifier 使用 SHA-256 十六进制摘要后写键。摘要不等于匿名化：用户名、手机号等
 *       低熵值仍可能被字典反推，所以原始值既不写 Redis 也不进日志。</li>
 *   <li>键长在摘要前先按可配置上限检查，避免超长用户名放大内存和哈希成本。</li>
 * </ul>
 */
public final class DefaultRateLimitKeyResolver implements RateLimitKeyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRateLimitKeyResolver.class);

    /** 版本段让键布局可以演进；修改布局时必须同时提升版本并接受旧键自然过期。 */
    private static final String KEY_INFIX = "rate-limit:v1:";

    private final String basePrefix;
    private final int maxKeyLength;
    private final int maxIdentifierLength;

    /**
     * MessageDigest 不是线程安全的；每个工作线程复用一个实例，避免每次请求都重新获取算法。
     * ThreadLocal 中只保存摘要器，不保存任何请求数据。
     */
    private final ThreadLocal<MessageDigest> digests = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时必须支持 SHA-256", exception);
        }
    });

    public DefaultRateLimitKeyResolver(RateLimitProperties properties, Environment environment) {
        this(resolveBasePrefix(properties, environment),
                properties.maxKeyLength(), properties.maxIdentifierLength());
    }

    /**
     * 允许测试或自定义装配直接指定前缀；仍执行同样的长度/格式校验。
     */
    DefaultRateLimitKeyResolver(String basePrefix, int maxKeyLength, int maxIdentifierLength) {
        if (basePrefix == null || basePrefix.isBlank()) {
            throw new RateLimitConfigurationException("Redis 限流前缀不能为空");
        }
        if (!basePrefix.endsWith(":")) {
            throw new RateLimitConfigurationException("Redis 限流前缀必须以冒号结尾");
        }
        if (basePrefix.length() > 256) {
            throw new RateLimitConfigurationException("Redis 限流前缀长度不能超过 256");
        }
        if (maxKeyLength < 64 || maxIdentifierLength < 1) {
            throw new RateLimitConfigurationException("Redis 键/标识长度上限必须为正");
        }
        this.basePrefix = basePrefix;
        this.maxKeyLength = maxKeyLength;
        this.maxIdentifierLength = maxIdentifierLength;
    }

    @Override
    public String resolve(RateLimitKey key, RateLimitAlgorithm algorithm) {
        if (key == null) {
            throw new RateLimitConfigurationException("限流键不能为 null");
        }
        if (algorithm == null || !algorithm.isConcrete()) {
            throw new RateLimitConfigurationException("解析 Redis 键时必须指定具体限流算法");
        }
        if (key.identifier().length() > maxIdentifierLength) {
            throw new RateLimitConfigurationException(
                    "限流标识长度不能超过 " + maxIdentifierLength + "（摘要前）");
        }

        String redisKey = basePrefix + key.namespace() + ":" + key.dimension() + ":"
                + algorithm.keySegment() + ":" + sha256Hex(key.identifier());
        if (redisKey.length() > maxKeyLength) {
            // 超长键不仅难排查，也会放大每个连接的命令传输与 Redis 内存占用。
            throw new RateLimitConfigurationException(
                    "生成的 Redis 限流键超过 max-key-length=" + maxKeyLength);
        }
        return redisKey;
    }

    @Override
    public String basePrefix() {
        return basePrefix;
    }

    private String sha256Hex(String value) {
        MessageDigest digest = digests.get();
        digest.reset();
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /**
     * 优先使用显式完整前缀；否则按环境/应用拼接。
     *
     * <p>环境未配置时取第一个 active profile，仍没有则用 {@code default} 并告警。应用名缺失
     * 直接启动失败：如果退化成固定字符串，所有未命名应用会共用一份额度，问题在生产才暴露。</p>
     */
    static String resolveBasePrefix(RateLimitProperties properties, Environment environment) {
        if (properties.keyPrefix() != null) {
            String configured = properties.keyPrefix();
            return configured.endsWith(":") ? configured : configured + ":";
        }

        String environmentName = properties.environment();
        if (environmentName == null) {
            String[] activeProfiles = environment.getActiveProfiles();
            environmentName = activeProfiles.length > 0 ? activeProfiles[0] : "default";
            LOG.warn("未配置 cloudprocure.rate-limit.environment，使用 {} 作为 Redis 键环境段；"
                    + "多环境共用 Redis 时必须显式配置", environmentName);
        }
        String applicationName = properties.application();
        if (applicationName == null) {
            applicationName = environment.getProperty("spring.application.name");
        }
        if (!StringUtils.hasText(applicationName)) {
            throw new IllegalStateException("common-rate-limit 需要 spring.application.name 或 "
                    + "cloudprocure.rate-limit.application 作为 Redis 键应用段");
        }

        requireNameSegment("environment", environmentName);
        requireNameSegment("application", applicationName);
        return "cp:" + environmentName.toLowerCase(Locale.ROOT) + ":" + applicationName
                + ":" + KEY_INFIX;
    }

    /**
     * 与配置属性中的校验保持一致；这里再校验一次，是为了覆盖直接构造解析器的测试/自定义装配。
     */
    private static void requireNameSegment(String field, String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new RateLimitConfigurationException("Redis 键 " + field + " 段不能为空且不能超过 128");
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)
                    || "*?[]:".indexOf(ch) >= 0
                    || (!Character.isLetterOrDigit(ch) && "._-".indexOf(ch) < 0)) {
                throw new RateLimitConfigurationException(
                        "Redis 键 " + field + " 段只能包含字母、数字、点、下划线和短横线");
            }
        }
    }
}
