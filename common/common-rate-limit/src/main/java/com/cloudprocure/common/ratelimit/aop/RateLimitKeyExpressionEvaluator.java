package com.cloudprocure.common.ratelimit.aop;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.cloudprocure.common.ratelimit.RateLimitConfigurationException;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * 受限 SpEL 键表达式求值器。
 *
 * <p>选择 {@link SimpleEvaluationContext#forReadOnlyDataBinding()} 而不是
 * {@code StandardEvaluationContext} 是安全边界：它不支持 Bean 引用（{@code @bean}）、
 * 类型引用（{@code T(...)}）、构造器和任意方法调用，只允许变量读取与公开属性读取。
 * 这避免了注解表达式变成任意 Bean 方法调用入口。</p>
 *
 * <p>变量只包括方法参数名、{@code p0/a0} 这类位置别名；不暴露 {@code #root}、
 * {@code #target} 或 BeanFactory。参数名依赖编译时的 {@code -parameters}，项目父 POM
 * 已开启；没有参数名时仍可用位置别名。</p>
 *
 * <p>表达式来自开发者写的注解，不是用户输入；但仍然要求求值失败时明确报错，
 * 不能退化成空键或所有请求共享一个额度。</p>
 */
final class RateLimitKeyExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer =
            new DefaultParameterNameDiscoverer();

    /**
     * 同一个注解表达式会被反复求值；解析结果不可变，可以安全缓存。
     * 表达式数量由代码中的注解数量决定，不会随请求增长。
     */
    private final ConcurrentMap<String, Expression> expressions = new ConcurrentHashMap<>();

    /**
     * @return 非空标识字符串；后续由 key resolver 做 SHA-256
     */
    String evaluate(String expressionText, Method method, Object[] arguments) {
        Expression expression = parse(expressionText);
        SimpleEvaluationContext context = createContext(method, arguments);

        Object value;
        try {
            value = expression.getValue(context);
        } catch (RuntimeException exception) {
            // 不把参数值拼进消息：表达式可能引用用户名/来源，异常消息不应泄露它们。
            throw new RateLimitConfigurationException(
                    "限流 key 表达式求值失败：method=" + method.toGenericString()
                            + ", expression=" + expressionText, exception);
        }
        return toIdentifier(value, expressionText, method);
    }

    private Expression parse(String expressionText) {
        Expression cached = expressions.get(expressionText);
        if (cached != null) {
            return cached;
        }
        Expression parsed;
        try {
            parsed = parser.parseExpression(expressionText);
        } catch (ParseException exception) {
            throw new RateLimitConfigurationException(
                    "限流 key 表达式语法错误：" + expressionText, exception);
        }
        Expression previous = expressions.putIfAbsent(expressionText, parsed);
        return previous == null ? parsed : previous;
    }

    /**
     * 把方法参数暴露成变量。最后一个可变参数按数组暴露，与 Spring 的
     * MethodBasedEvaluationContext 行为保持一致，避免表达式对 varargs 的预期落空。
     */
    private SimpleEvaluationContext createContext(Method method, Object[] arguments) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        int parameterCount = method.getParameterCount();

        for (int index = 0; index < parameterCount; index++) {
            Object argument;
            if (safeArguments.length > parameterCount && index == parameterCount - 1) {
                argument = Arrays.copyOfRange(safeArguments, index, safeArguments.length);
            } else if (index < safeArguments.length) {
                argument = safeArguments[index];
            } else {
                argument = null;
            }
            context.setVariable("p" + index, argument);
            context.setVariable("a" + index, argument);
            if (parameterNames != null && index < parameterNames.length
                    && parameterNames[index] != null) {
                context.setVariable(parameterNames[index], argument);
            }
        }
        return context;
    }

    /**
     * 只接受稳定、可预期的标识类型。任意对象的 toString() 可能包含地址、集合内容，
     * 甚至带副作用的实现；让它成为限流键既不稳定也难以审计。
     */
    private String toIdentifier(Object value, String expressionText, Method method) {
        String identifier = switch (value) {
            case null -> throw new RateLimitConfigurationException(
                    "限流 key 表达式求值为 null：method=" + method.toGenericString()
                            + ", expression=" + expressionText);
            case CharSequence text -> text.toString();
            case Number number -> number.toString();
            case UUID uuid -> uuid.toString();
            case Enum<?> enumValue -> enumValue.name();
            default -> throw new RateLimitConfigurationException(
                    "限流 key 表达式必须返回字符串/数字/UUID/枚举：method="
                            + method.toGenericString() + ", type=" + value.getClass().getName());
        };
        if (identifier.isBlank()) {
            throw new RateLimitConfigurationException(
                    "限流 key 表达式结果不能为空：method=" + method.toGenericString());
        }
        return identifier;
    }
}
