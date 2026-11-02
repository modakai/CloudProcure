package com.cloudprocure.common.ratelimit.core;

import java.util.List;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 算法到 Lua 脚本的映射。
 *
 * <p>脚本作为单例加载：{@link DefaultRedisScript} 会缓存 SHA1，后续执行走 EVALSHA；
 * 如果在每次请求时重新读 classpath，既浪费 I/O，也会丢失脚本缓存带来的收益。</p>
 *
 * <p>构造时立即读取脚本文本。资源缺失或语法加载失败应在启动阶段暴露，而不是等第一个
 * 用户请求到达时才 503。</p>
 */
enum RateLimitScript {

    FIXED_WINDOW("lua/fixed_window.lua"),
    SLIDING_WINDOW("lua/sliding_window.lua"),
    TOKEN_BUCKET("lua/token_bucket.lua"),
    LEAKY_BUCKET("lua/leaky_bucket.lua");

    private final DefaultRedisScript<List> script;

    @SuppressWarnings({"rawtypes", "unchecked"})
    RateLimitScript(String location) {
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setLocation(new ClassPathResource(location));
        // Lua 返回的是 {allowed, retry_after_ms, remaining} 整数数组，必须声明为多值返回。
        loaded.setResultType(List.class);
        loaded.afterPropertiesSet();
        // 触发一次资源读取，让缺失的脚本在应用启动时失败。
        loaded.getScriptAsString();
        this.script = loaded;
    }

    DefaultRedisScript<List> script() {
        return script;
    }

    static RateLimitScript forAlgorithm(RateLimitAlgorithm algorithm) {
        return switch (algorithm) {
            case FIXED_WINDOW -> FIXED_WINDOW;
            case SLIDING_WINDOW -> SLIDING_WINDOW;
            case TOKEN_BUCKET -> TOKEN_BUCKET;
            case LEAKY_BUCKET -> LEAKY_BUCKET;
            // DEFAULT 必须由调用方先解析成配置默认值；到这里说明解析逻辑有缺陷。
            case DEFAULT -> throw new IllegalArgumentException("DEFAULT 不是可执行的限流算法");
        };
    }
}
