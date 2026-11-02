package com.cloudprocure.common.ratelimit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;

/**
 * 在 Spring Bean 方法（或类）上声明限流。
 *
 * <p><b>为什么 window 是 String：</b>Java 注解元素只能是基本类型、String、Class、枚举、
 * 注解或它们的数组，不能写 {@code Duration}。issue 中的
 * {@code Duration window()} 在 Java 语法上无法编译，因此这里使用
 * {@code "5m"} / {@code "PT5M"} 形式，由 Spring Boot 的 DurationStyle 解析。</p>
 *
 * <p><b>只适用于可被 Spring 代理的 Bean 方法：</b>Controller/Service 等由容器管理的 public
 * 方法可以被拦截；同一个 Bean 内部 self-invocation、private/final 方法、WebFlux 事件循环
 * 上的方法不保证生效。Filter/Security 链请直接调用程序化 {@code RateLimiter}。</p>
 *
 * <p><b>key 是 SpEL 标识表达式：</b>求值结果会作为 RateLimitKey.identifier，经 SHA-256
 * 摘要后才进入 Redis。表达式只能访问方法参数和 {@code p0/a0} 别名，不能调用 Bean、类型或
 * 任意方法；求值失败、结果为 null 或空串都会抛配置异常，不会退化成所有请求共享一个额度。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 标识表达式，例如 {@code "'login:' + #loginName"} 或 {@code "#request.userId"}。
     *
     * <p>只允许读取方法参数及其公开属性；SpEL 的 Bean 引用、类型引用、构造器和方法调用被禁用。</p>
     */
    String key();

    /**
     * 单次调用的额度。{@code -1} 表示使用
     * {@code cloudprocure.rate-limit.default-limit}；其他值必须为正。
     */
    int limit() default -1;

    /**
     * 窗口长度，支持 {@code 5m} 这类简单写法和 {@code PT5M} ISO-8601 写法。
     * 空串表示使用 {@code cloudprocure.rate-limit.default-window}。
     */
    String window() default "";

    /**
     * 算法。{@link RateLimitAlgorithm#DEFAULT} 表示使用全局默认算法。
     */
    RateLimitAlgorithm algorithm() default RateLimitAlgorithm.DEFAULT;

    /**
     * 业务命名空间前缀，例如 {@code login} 或 {@code identity:login}。
     *
     * <p>为空时使用目标类的全限定名，避免不同类的同名方法共享额度。该值只允许字母、数字和
     * {@code . _ : $ # -}，不能包含空白、控制字符或 Redis 通配符。</p>
     */
    String prefix() default "";

    /**
     * 限流维度，例如 {@code account} 或 {@code source}。
     *
     * <p>为空时使用方法名；同一方法的重载会共享维度，需要区分时显式填写。</p>
     */
    String dimension() default "";
}
