package com.cloudprocure.common.ratelimit.core;

import java.time.Duration;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitKey;
import com.cloudprocure.common.ratelimit.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流决策日志。
 *
 * <p>默认打印到 SLF4J（Boot 默认即控制台）。日志只包含命名空间、维度、算法、额度、
 * 等待时间和耗时，不包含原始标识、SHA-256 摘要、完整 Redis 键或 Redis 凭据；这些信息
 * 一旦进日志，就可能被用来关联账号或直接复用凭据。</p>
 *
 * <p>允许/拒绝决策默认开启，便于学习和本地观察；生产高流量服务应把
 * {@code cloudprocure.rate-limit.log-decisions} 设为 false，避免每个请求写一行日志。
 * Redis 不可用的 ERROR 不受该开关影响，故障必须可见。</p>
 */
public class RateLimitDecisionLogger {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitDecisionLogger.class);

    private final boolean logDecisions;

    public RateLimitDecisionLogger(RateLimitProperties properties) {
        this.logDecisions = properties.logDecisions();
    }

    /** 启动时打印一次实际生效的前缀与默认值，便于确认环境和应用段没有配错。 */
    public void startup(String basePrefix, RateLimitProperties properties) {
        LOG.info("common-rate-limit 已启用：basePrefix={}, defaultAlgorithm={}, defaultLimit={}, "
                        + "defaultWindow={}, logDecisions={}",
                basePrefix, properties.defaultAlgorithmName(), properties.defaultLimit(),
                properties.defaultWindow(), properties.logDecisions());
    }

    public void allowed(RateLimitKey key, RateLimitAlgorithm algorithm, int limit,
                        Duration window, long remaining, long elapsedMicros) {
        if (logDecisions) {
            LOG.info("限流放行：namespace={}, dimension={}, algorithm={}, limit={}, window={}, "
                            + "remaining={}, elapsedUs={}",
                    key.namespace(), key.dimension(), algorithm.keySegment(), limit,
                    window.toMillis() + "ms", remaining, elapsedMicros);
        }
    }

    public void rejected(RateLimitKey key, RateLimitAlgorithm algorithm, int limit,
                         Duration window, long retryAfterSeconds, long elapsedMicros) {
        if (logDecisions) {
            // 拒绝是安全相关事件，用 WARN 让默认控制台也能看到；仍不记录标识。
            LOG.warn("限流拒绝：namespace={}, dimension={}, algorithm={}, limit={}, window={}, "
                            + "retryAfterSeconds={}, elapsedUs={}",
                    key.namespace(), key.dimension(), algorithm.keySegment(), limit,
                    window.toMillis() + "ms", retryAfterSeconds, elapsedMicros);
        }
    }

    /**
     * Redis/脚本故障。
     *
     * <p>ERROR 级别只输出异常类名，完整堆栈放到 DEBUG：部分连接异常的 message 可能
     * 包含连接串，不能默认写进控制台。需要排查时临时把本类日志级别调到 DEBUG。</p>
     */
    public void unavailable(RateLimitKey key, RateLimitAlgorithm algorithm, RuntimeException error) {
        LOG.error("限流存储不可用，按 fail-closed 拒绝调用：namespace={}, dimension={}, "
                        + "algorithm={}, error={}",
                key.namespace(), key.dimension(), algorithm.keySegment(), error.getClass().getName());
        if (LOG.isDebugEnabled()) {
            LOG.debug("限流 Redis 异常详情", error);
        }
    }
}
