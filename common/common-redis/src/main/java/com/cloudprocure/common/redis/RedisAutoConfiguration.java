package com.cloudprocure.common.redis;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;
import tools.jackson.databind.DatabindContext;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.jsontype.impl.DefaultTypeResolverBuilder;

/**
 * common-redis 自动配置：统一 JSON 序列化、缓存键前缀和过期策略。
 *
 * <p>排在 Boot 的 {@link DataRedisAutoConfiguration} 之前，是为了让本模块定义的
 * {@code redisTemplate} 先注册，Boot 的同名默认 Bean 再按
 * {@code @ConditionalOnMissingBean} 退让；连接工厂本身仍由 Boot 负责创建。</p>
 */
@AutoConfiguration(before = DataRedisAutoConfiguration.class)
@ConditionalOnClass({RedisConnectionFactory.class, RedisCacheManagerBuilderCustomizer.class})
@EnableConfigurationProperties(RedisCacheProperties.class)
public class RedisAutoConfiguration {

    /**
     * 缓存值与 Hash 值共用的序列化器。
     *
     * <p>开启默认类型信息是为了让 {@code @Cacheable} 的方法返回值能还原成原类型；
     * 同时用白名单限制可反序列化的子类型，避免把 Redis 中的任意 {@code @class}
     * 当成可信输入，形成反序列化利用链。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "cloudProcureRedisValueSerializer")
    RedisSerializer<Object> cloudProcureRedisValueSerializer(ObjectProvider<JsonMapper> jsonMapper) {
        JsonMapper mapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
        return createRedisValueSerializer(mapper);
    }

    /**
     * 覆盖 Boot 默认的 JDK 序列化模板；应用自己声明同名 Bean 时本 Bean 退让。
     *
     * <p>连接工厂在 Bean 实例化阶段解析，此时 Boot 的 Redis 自动配置已经完成注册，
     * 因此可以安全地排在 Boot 自动配置之前而不依赖其 Bean 已存在。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            @Qualifier("cloudProcureRedisValueSerializer") RedisSerializer<Object> valueSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(valueSerializer);
        return template;
    }

    @Bean
    RedisCacheManagerBuilderCustomizer cloudProcureRedisCacheCustomizer(
            RedisCacheProperties properties,
            @Qualifier("cloudProcureRedisValueSerializer") RedisSerializer<Object> valueSerializer,
            Environment environment) {
        // 前缀在真正构建 CacheManager 时才解析；只使用 RedisTemplate、未启用缓存的应用
        // 不应因为缺少 spring.application.name 或前缀配置而启动失败。
        return new RedisCacheCustomizer(properties, valueSerializer, environment);
    }

    static RedisSerializer<Object> createRedisValueSerializer(JsonMapper mapper) {
        // 只允许本项目的 DTO 和可安全反序列化的 JDK 值类型；数组用于 List/Map 之外的值载体。
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.cloudprocure.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                // 不能用 allowIfSubTypeIsArray()：它会放行任意组件类型的数组，
                // 绕过上面的包名白名单；这里只接受组件类型仍在白名单内的数组。
                .allowIfSubType(RedisAutoConfiguration::isAllowedArrayType)
                .build();

        // Jackson 3 的 NON_FINAL_AND_ENUMS 不含 record；记录是 final，必须额外打开类型信息，
        // 否则 @Cacheable 返回 record 时只能反序列化成 LinkedHashMap。
        var typing = new DefaultTypeResolverBuilder(
                validator, DefaultTyping.NON_FINAL_AND_ENUMS, JsonTypeInfo.As.PROPERTY) {
            @Override
            public boolean useForType(JavaType type) {
                // 记录和数组都是 final，不在 NON_FINAL_AND_ENUMS 的默认判定内。
                return type.isRecordType() || type.isArrayType() || super.useForType(type);
            }
        };

        RedisSerializer<Object> delegate = GenericJacksonJsonRedisSerializer.builder(mapper::rebuild)
                .customize(builder -> builder
                        .setDefaultTyping(typing)
                        // 允许 DTO 新增字段后继续读取旧缓存；缓存会在 TTL 内自然替换。
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        // String/Instant 等自然类型没有 @class，不能按缺失类型标识报错。
                        .configure(DeserializationFeature.FAIL_ON_MISSING_EXTERNAL_TYPE_ID_PROPERTY, false))
                .build();
        return new JsonRedisSerializer(delegate);
    }

    private static boolean isAllowedArrayType(DatabindContext context, Class<?> type) {
        if (!type.isArray()) {
            return false;
        }
        Class<?> component = type;
        while (component.isArray()) {
            component = component.getComponentType();
        }
        if (component.isPrimitive() || component == String.class) {
            return true;
        }
        String name = component.getName();
        return name.startsWith("com.cloudprocure.")
                || name.startsWith("java.util.")
                || name.startsWith("java.time.")
                || name.startsWith("java.math.");
    }

    static String resolveKeyPrefix(RedisCacheProperties properties, Environment environment) {
        String prefix = properties.keyPrefix();
        if (!StringUtils.hasText(prefix)) {
            String applicationName = environment.getProperty("spring.application.name");
            if (!StringUtils.hasText(applicationName)) {
                throw new IllegalStateException(
                        "common-redis 缓存需要 spring.application.name 或 "
                                + "cloudprocure.redis.cache.key-prefix 作为键命名空间");
            }
            // 不能只靠缓存名区分服务：不同服务的 "product" 缓存会写到同一个键上。
            prefix = "cp:" + applicationName + ":cache:";
        }
        if (!prefix.endsWith(":")) {
            prefix = prefix + ":";
        }
        if (prefix.chars().anyMatch(ch -> "*?[]".indexOf(ch) >= 0)) {
            throw new IllegalArgumentException(
                    "cloudprocure.redis.cache.key-prefix 不能包含 Redis 通配符 * ? [ ]");
        }
        if (prefix.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "cloudprocure.redis.cache.key-prefix 不能包含空白字符");
        }
        return prefix;
    }
}
