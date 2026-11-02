package com.cloudprocure.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitKey;
import com.cloudprocure.common.ratelimit.RateLimitResult;
import com.cloudprocure.common.ratelimit.RateLimiter;

/**
 * 登录尝试额度：在密码校验之前预占额度，使并发、跨实例、重启后的登录同样受限。
 *
 * <p>本类只是 identity 的业务薄封装，真正的计数、TTL、原子性和 Redis 故障处理都在
 * {@link RateLimiter}。它保留 {@link #reserve(String, String)} 这个小接口：
 * 返回 0 表示允许，正数表示再次尝试前至少等待的秒数；Redis 无法给出可靠结果时抛
 * {@code RateLimitUnavailableException}，由过滤器映射为 503。</p>
 *
 * <p>顺序固定为“先来源、后账号”：账号已经超限时仍然消耗来源额度，防止一个来源持续
 * 轰炸被限速的账号；来源被拒绝时不再创建账号计数。两个单键脚本顺序执行，不是跨键事务，
 * 中途故障会保守多消耗，但不会放行。</p>
 *
 * <p>算法固定为固定窗口，与迁移前的内存实现保持一致。identity 的规则由
 * {@code identity.login-rate-limit.*} 配置；模块的全局默认算法变化不应悄悄改变登录语义。</p>
 */
public final class LoginAttemptLimiter {

    private static final String NAMESPACE = "identity:login";
    private static final String ACCOUNT_DIMENSION = "account";
    private static final String SOURCE_DIMENSION = "source";

    /**
     * 缺少用户名时仍要占用一个稳定的账号维度，否则攻击者可以靠发送空用户名绕过账号限速。
     * 该值不是真实账号；hash 后写入 Redis，不会出现在响应或日志里。
     */
    private static final String MISSING_LOGIN_NAME = "<missing-login-name>";
    private static final String UNKNOWN_SOURCE = "<unknown-source>";

    /**
     * 账号先做一次固定长度摘要，再交给限流模块。
     *
     * <p>模块的 RateLimitKey 会拒绝控制字符和超过 4096 的标识；如果直接把原始用户名交过去，
     * 一个带换行或超长用户名的请求会让限流抛配置异常，等于绕过账号维度计数（只剩来源维度）。
     * 先摘要成 64 位十六进制可以消除长度/字符集差异，同时保持 trim 和大小写语义。</p>
     */
    private static final ThreadLocal<MessageDigest> DIGESTS = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时必须支持 SHA-256", exception);
        }
    });

    private final RateLimiter rateLimiter;
    private final int accountLimit;
    private final Duration accountWindow;
    private final int sourceLimit;
    private final Duration sourceWindow;

    public LoginAttemptLimiter(RateLimiter rateLimiter,
                               int accountLimit, Duration accountWindow,
                               int sourceLimit, Duration sourceWindow) {
        if (rateLimiter == null) {
            throw new IllegalArgumentException("登录限速器不能为 null");
        }
        if (accountLimit < 1 || sourceLimit < 1
                || accountWindow == null || sourceWindow == null
                || accountWindow.isZero() || accountWindow.isNegative()
                || sourceWindow.isZero() || sourceWindow.isNegative()
                || accountWindow.toMillis() <= 0 || sourceWindow.toMillis() <= 0) {
            throw new IllegalArgumentException("登录限速额度与窗口必须为正数");
        }
        this.rateLimiter = rateLimiter;
        this.accountLimit = accountLimit;
        this.accountWindow = accountWindow;
        this.sourceLimit = sourceLimit;
        this.sourceWindow = sourceWindow;
    }

    /**
     * 预占一次登录尝试。
     *
     * @return 0 表示允许；否则为再次尝试前至少等待的秒数
     * @throws com.cloudprocure.common.ratelimit.RateLimitUnavailableException
     *         Redis 不可用或限流状态损坏；调用方必须停止认证并返回 503
     */
    public long reserve(String loginName, String source) {
        // 先来源：账号已超限也要继续消耗来源额度；来源拒绝则不创建账号计数。
        RateLimitResult sourceResult = rateLimiter.tryAcquire(
                new RateLimitKey(NAMESPACE, SOURCE_DIMENSION, normalize(source, UNKNOWN_SOURCE)),
                sourceLimit, sourceWindow, RateLimitAlgorithm.FIXED_WINDOW);
        if (!sourceResult.allowed()) {
            return sourceResult.retryAfterSeconds();
        }

        // 账号沿用框架登录过滤器的 trim 语义；不改变大小写，避免合并不同账号。
        RateLimitResult accountResult = rateLimiter.tryAcquire(
                new RateLimitKey(NAMESPACE, ACCOUNT_DIMENSION, accountIdentifier(loginName)),
                accountLimit, accountWindow, RateLimitAlgorithm.FIXED_WINDOW);
        return accountResult.allowed() ? 0 : accountResult.retryAfterSeconds();
    }

    private static String accountIdentifier(String loginName) {
        String normalized = normalize(loginName, MISSING_LOGIN_NAME);
        MessageDigest digest = DIGESTS.get();
        digest.reset();
        return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static String normalize(String value, String fallback) {
        // 与框架登录过滤器一致：先 trim 再摘要；大小写保持原样，不能把不同账号合并。
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
