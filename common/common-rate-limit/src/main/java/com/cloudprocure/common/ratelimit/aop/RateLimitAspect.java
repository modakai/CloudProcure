package com.cloudprocure.common.ratelimit.aop;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;

import com.cloudprocure.common.ratelimit.RateLimitAlgorithm;
import com.cloudprocure.common.ratelimit.RateLimitConfigurationException;
import com.cloudprocure.common.ratelimit.RateLimitExceededException;
import com.cloudprocure.common.ratelimit.RateLimitKey;
import com.cloudprocure.common.ratelimit.RateLimitResult;
import com.cloudprocure.common.ratelimit.RateLimiter;
import com.cloudprocure.common.ratelimit.annotation.RateLimit;
import com.cloudprocure.common.ratelimit.config.RateLimitProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * 拦截 {@link RateLimit} 标注的 Spring Bean 方法。
 *
 * <p><b>顺序：</b>实现 {@link Ordered} 并由配置决定顺序，默认值高于事务等常见拦截器，
 * 使超限请求在进入数据库/密码校验之前就被拒绝。若应用需要在限流前执行审计/租户解析，
 * 可通过 {@code cloudprocure.rate-limit.aop-order} 调整。</p>
 *
 * <p><b>边界：</b>Spring AOP 只能拦截容器代理后的调用。同一个 Bean 内部 A 调 B（self-invocation）、
 * private/final 方法、非 Spring Bean 都不生效；这些入口请改用程序化 {@link RateLimiter}。
 * 本类不会为绕过代理的调用提供“看起来生效”的假保证。</p>
 *
 * <p><b>合并规则：</b>方法注解覆盖类注解的同名属性；方法只覆盖显式设置的值，
 * {@code limit=-1}/{@code window=""}/{@code algorithm=DEFAULT} 表示继承类注解或全局默认。
 * 方法上没有注解时，类注解作为方法默认值。</p>
 */
@Aspect
public final class RateLimitAspect implements Ordered {

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final RateLimitKeyExpressionEvaluator evaluator = new RateLimitKeyExpressionEvaluator();
    private final int order;

    public RateLimitAspect(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
        this.order = properties.aopOrder();
    }

    @Around("@annotation(com.cloudprocure.common.ratelimit.annotation.RateLimit) "
            + "|| @within(com.cloudprocure.common.ratelimit.annotation.RateLimit)")
    public Object apply(ProceedingJoinPoint joinPoint) throws Throwable {
        Invocation invocation = resolveInvocation(joinPoint);
        if (invocation == null) {
            // 理论上 pointcut 已保证命中；这里保留防御分支，避免未来改 pointcut 后误拦截所有方法。
            return joinPoint.proceed();
        }
        String identifier = evaluator.evaluate(invocation.keyExpression(), invocation.method(),
                joinPoint.getArgs());
        RateLimitKey key = new RateLimitKey(invocation.namespace(), invocation.dimension(), identifier);
        RateLimitResult result = limiter.tryAcquire(key, invocation.limit(), invocation.window(),
                invocation.algorithm());
        if (!result.allowed()) {
            // 不在这里写 HTTP 响应：AOP 可能用于消息消费者或服务层，不能假设 MVC 存在。
            throw new RateLimitExceededException(result.retryAfterSeconds());
        }
        return joinPoint.proceed();
    }

    private Invocation resolveInvocation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null
                ? method.getDeclaringClass() : AopUtils.getTargetClass(joinPoint.getTarget());
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

        RateLimit methodAnnotation = findMethodAnnotation(specificMethod, method, targetClass);
        RateLimit typeAnnotation = findTypeAnnotation(targetClass);
        if (methodAnnotation == null && typeAnnotation == null) {
            return null;
        }

        String keyExpression = methodAnnotation != null ? methodAnnotation.key() : typeAnnotation.key();
        if (!StringUtils.hasText(keyExpression)) {
            throw new RateLimitConfigurationException(
                    "@RateLimit.key 不能为空：method=" + specificMethod.toGenericString());
        }

        int limit = resolveLimit(methodAnnotation, typeAnnotation, specificMethod);
        Duration window = resolveWindow(methodAnnotation, typeAnnotation, specificMethod);
        RateLimitAlgorithm algorithm = resolveAlgorithm(methodAnnotation, typeAnnotation);
        String namespace = firstNonBlank(
                methodAnnotation == null ? null : methodAnnotation.prefix(),
                typeAnnotation == null ? null : typeAnnotation.prefix(),
                targetClass.getName());
        String dimension = firstNonBlank(
                methodAnnotation == null ? null : methodAnnotation.dimension(),
                typeAnnotation == null ? null : typeAnnotation.dimension(),
                specificMethod.getName());

        return new Invocation(specificMethod, keyExpression, namespace, dimension,
                limit, window, algorithm);
    }

    /**
     * 先查实现方法，再查原始接口/父类方法。这样注解写在接口方法上、实现类没有重复注解时
     * 仍能读取；但 Spring AOP 是否拦截仍取决于代理类型与 pointcut，不能仅靠这里保证。
     */
    private RateLimit findMethodAnnotation(Method specificMethod, Method declaredMethod,
                                           Class<?> targetClass) {
        RateLimit annotation = AnnotatedElementUtils.findMergedAnnotation(
                specificMethod, RateLimit.class);
        if (annotation != null) {
            return annotation;
        }
        if (!specificMethod.equals(declaredMethod)) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(declaredMethod, RateLimit.class);
            if (annotation != null) {
                return annotation;
            }
        }
        Set<Class<?>> interfaces = ClassUtils.getAllInterfacesForClassAsSet(targetClass);
        for (Class<?> iface : interfaces) {
            Method candidate = ReflectionUtils.findMethod(iface, declaredMethod.getName(),
                    declaredMethod.getParameterTypes());
            if (candidate != null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(candidate, RateLimit.class);
                if (annotation != null) {
                    return annotation;
                }
            }
        }
        for (Class<?> parent = targetClass.getSuperclass();
                parent != null && parent != Object.class; parent = parent.getSuperclass()) {
            Method candidate = ReflectionUtils.findMethod(parent, declaredMethod.getName(),
                    declaredMethod.getParameterTypes());
            if (candidate != null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(candidate, RateLimit.class);
                if (annotation != null) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private RateLimit findTypeAnnotation(Class<?> targetClass) {
        RateLimit annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, RateLimit.class);
        if (annotation != null) {
            return annotation;
        }
        for (Class<?> iface : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(iface, RateLimit.class);
            if (annotation != null) {
                return annotation;
            }
        }
        for (Class<?> parent = targetClass.getSuperclass();
                parent != null && parent != Object.class; parent = parent.getSuperclass()) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(parent, RateLimit.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private int resolveLimit(RateLimit methodAnnotation, RateLimit typeAnnotation, Method method) {
        int methodLimit = methodAnnotation == null ? -1 : methodAnnotation.limit();
        if (methodLimit != -1) {
            return validateLimit(methodLimit, method);
        }
        int typeLimit = typeAnnotation == null ? -1 : typeAnnotation.limit();
        if (typeLimit != -1) {
            return validateLimit(typeLimit, method);
        }
        return properties.defaultLimit();
    }

    private int validateLimit(int limit, Method method) {
        if (limit <= 0) {
            throw new RateLimitConfigurationException(
                    "@RateLimit.limit 必须为正，或使用 -1 表示默认值：method=" + method.toGenericString());
        }
        if (limit > properties.maxLimit()) {
            throw new RateLimitConfigurationException(
                    "@RateLimit.limit 超过配置上限 " + properties.maxLimit()
                            + "：method=" + method.toGenericString());
        }
        return limit;
    }

    private Duration resolveWindow(RateLimit methodAnnotation, RateLimit typeAnnotation, Method method) {
        if (methodAnnotation != null && StringUtils.hasText(methodAnnotation.window())) {
            return parseWindow(methodAnnotation.window(), method);
        }
        if (typeAnnotation != null && StringUtils.hasText(typeAnnotation.window())) {
            return parseWindow(typeAnnotation.window(), method);
        }
        return properties.defaultWindow();
    }

    private Duration parseWindow(String text, Method method) {
        try {
            return DurationStyle.detectAndParse(text);
        } catch (IllegalArgumentException exception) {
            throw new RateLimitConfigurationException(
                    "@RateLimit.window 不是合法时长（示例：5m 或 PT5M）：method="
                            + method.toGenericString(), exception);
        }
    }

    private RateLimitAlgorithm resolveAlgorithm(RateLimit methodAnnotation, RateLimit typeAnnotation) {
        if (methodAnnotation != null && methodAnnotation.algorithm() != RateLimitAlgorithm.DEFAULT) {
            return methodAnnotation.algorithm();
        }
        if (typeAnnotation != null && typeAnnotation.algorithm() != RateLimitAlgorithm.DEFAULT) {
            return typeAnnotation.algorithm();
        }
        return properties.defaultAlgorithm();
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return fallback;
    }

    @Override
    public int getOrder() {
        return order;
    }

    private record Invocation(Method method, String keyExpression, String namespace, String dimension,
                              int limit, Duration window, RateLimitAlgorithm algorithm) {
    }
}
