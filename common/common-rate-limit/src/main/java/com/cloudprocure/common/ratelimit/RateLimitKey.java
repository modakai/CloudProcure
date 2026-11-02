package com.cloudprocure.common.ratelimit;

/**
 * 限流维度键：业务命名空间 + 维度 + 原始标识。
 *
 * <p>原始标识（用户名、来源地址、Token 摘要前的值）只在这里短暂存在，写入 Redis 前会由
 * {@code DefaultRateLimitKeyResolver} 做 SHA-256 摘要。键对象本身不包含 Redis 前缀，
 * 这样同一业务键可以被不同应用/环境复用，也方便自定义解析器替换存储布局。</p>
 *
 * <p>{@link #toString()} 故意隐藏 identifier，防止调用方把键直接打进日志或异常消息。
 * 需要排查时只能记录 namespace 与 dimension，不能记录原始标识或摘要。</p>
 *
 * @param namespace 业务命名空间，例如 {@code identity:login}
 * @param dimension 限流维度，例如 {@code account}、{@code source}
 * @param identifier 原始标识；构造时只做 trim 与长度/控制字符校验，不做大小写或 Unicode 归一化
 */
public record RateLimitKey(String namespace, String dimension, String identifier) {

    /** 单个命名空间/维度段的最大长度；过长的段通常意味着调用方把用户数据塞进了错误的位置。 */
    private static final int MAX_SEGMENT_LENGTH = 128;

    /**
     * 标识的硬上限。摘要前必须先限制长度，否则攻击者可以用超长用户名制造
     * 不必要的内存分配与哈希 CPU 消耗；可配置上限还会更小。
     */
    private static final int MAX_IDENTIFIER_LENGTH = 4096;

    public RateLimitKey {
        namespace = requireSegment("namespace", namespace);
        dimension = requireSegment("dimension", dimension);
        identifier = requireIdentifier(identifier);
    }

    /**
     * 语义化工厂方法，便于调用方在业务代码中一眼看出三个字段的含义。
     */
    public static RateLimitKey of(String namespace, String dimension, String identifier) {
        return new RateLimitKey(namespace, dimension, identifier);
    }

    /**
     * 段只允许可打印的字母、数字和少量分隔符。
     *
     * <p>拒绝空白与控制字符是为了避免日志注入和 Redis 键中出现不可见字符；拒绝
     * {@code * ? [ ]} 是因为这些字符会被 SCAN MATCH 当通配符，清理测试前缀时可能误删其他键。
     * 这里不拒绝冒号，因为 {@code identity:login} 这类分层命名空间是预期用法。</p>
     */
    private static String requireSegment(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new RateLimitConfigurationException("RateLimitKey." + field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_SEGMENT_LENGTH) {
            throw new RateLimitConfigurationException(
                    "RateLimitKey." + field + " 长度不能超过 " + MAX_SEGMENT_LENGTH);
        }
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            boolean allowedPunctuation = "._:$#-".indexOf(ch) >= 0;
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)
                    || "*?[]".indexOf(ch) >= 0
                    || (!Character.isLetterOrDigit(ch) && !allowedPunctuation)) {
                throw new RateLimitConfigurationException(
                        "RateLimitKey." + field + " 含非法字符: U+" + Integer.toHexString(ch));
            }
        }
        return normalized;
    }

    /**
     * 标识可以包含任意可打印字符（例如邮箱、手机号、UA 片段），因为它不会被直接写进 Redis 键。
     *
     * <p>只 trim 不改变大小写：登录名等标识的大小写语义由业务决定，公共模块擅自
     * {@code toLowerCase} 会把不同账号合并到同一个额度。内部空白也不折叠，避免改变原始语义。</p>
     */
    private static String requireIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new RateLimitConfigurationException("RateLimitKey.identifier 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_IDENTIFIER_LENGTH) {
            throw new RateLimitConfigurationException(
                    "RateLimitKey.identifier 长度不能超过 " + MAX_IDENTIFIER_LENGTH);
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new RateLimitConfigurationException("RateLimitKey.identifier 不能包含控制字符");
            }
        }
        return normalized;
    }

    /** 不输出原始标识，避免日志/异常消息泄露用户名、来源或 Token 派生值。 */
    @Override
    public String toString() {
        return "RateLimitKey[namespace=" + namespace + ", dimension=" + dimension + ", identifier=***]";
    }
}
