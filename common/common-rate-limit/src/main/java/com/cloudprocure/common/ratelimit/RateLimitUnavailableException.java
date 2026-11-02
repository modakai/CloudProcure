package com.cloudprocure.common.ratelimit;

/**
 * 限流存储不可用，或存储中的限流状态损坏。
 *
 * <p>这是 fail-closed 的唯一出口：连接失败、认证失败、超时、Lua 脚本报错、计数类型/值异常
 * 都转换为本异常，绝不允许返回 {@code allowed=true}，也不会静默回退到内存计数。</p>
 *
 * <p>调用方（Servlet Filter、Security、消息消费者）应把本异常映射为 503，并停止进入
 * 受保护逻辑。异常消息不包含 Redis 凭据、原始标识或完整键。</p>
 */
public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(String message) {
        super(message);
    }

    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
