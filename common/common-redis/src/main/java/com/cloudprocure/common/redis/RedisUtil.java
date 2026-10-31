package com.cloudprocure.common.redis;

import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 静态 Redis 操作门面，支持泛型返回值与 {@code RedisUtil.getKey(...)} 这类直接调用。
 *
 * <p>模板由 {@link RedisAutoConfiguration} 在容器启动时注入，应用不需要再注入 Bean。
 * 之所以保留一个静态 holder：静态方法没有实例可注入模板，只能在启动阶段建立一次引用。
 * 这带来两个必须知道的限制：同一 JVM 只能有一个活动 RedisTemplate；测试或同一 JVM 内启动
 * 多个 Spring 上下文时，后启动的上下文会覆盖引用。上下文关闭时使用 compare-and-set 清理，
 * 不会把后来上下文设置的模板误清空。</p>
 *
 * <p>泛型 {@link #get(String)} 是 unchecked 转换：编译期按调用方赋值目标推断 T，运行期仍是
 * JSON 反序列化得到的实际类型。无法确认类型时使用 {@link #get(String, Class)}，或在调用处
 * 自行承担 ClassCastException。值仍受本模块 JSON 白名单约束。</p>
 *
 * <p>本类不提供 {@code INCR}/{@code DECR}、分布式锁和 {@code KEYS}/{@code SCAN} 封装：
 * JSON 值模板不能安全地做数字原子操作，锁与扫描需要独立设计。这些场景请直接使用
 * {@code StringRedisTemplate}/Lua 或 {@link RedisTemplate}。键不自动加缓存前缀，
 * 调用方必须自行加入环境/服务/租户命名空间。</p>
 */
public final class RedisUtil {

    private static final AtomicReference<RedisTemplate<String, Object>> TEMPLATE =
            new AtomicReference<>();

    private RedisUtil() {
    }

    /** 由自动配置在生命周期 Bean 创建时调用；应用代码不应直接调用。 */
    static void init(RedisTemplate<String, Object> template) {
        TEMPLATE.set(Objects.requireNonNull(template, "redisTemplate 不能为空"));
    }

    /** 只清理当前上下文设置的模板，避免关闭旧上下文时清掉新上下文的引用。 */
    static void clear(RedisTemplate<String, Object> template) {
        TEMPLATE.compareAndSet(template, null);
    }

    static boolean isInitialized() {
        return TEMPLATE.get() != null;
    }

    private static RedisTemplate<String, Object> template() {
        RedisTemplate<String, Object> template = TEMPLATE.get();
        if (template == null) {
            throw new IllegalStateException(
                    "RedisUtil 尚未初始化：需要 common-redis 自动配置并提供 redisTemplate");
        }
        return template;
    }

    /**
     * 按 key 读取值，返回类型由调用方赋值目标推断。
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        return (T) template().opsForValue().get(requireKey(key));
    }

    /**
     * 按 key 读取值并显式校验目标类型；类型不匹配时抛出 ClassCastException。
     */
    public static <T> T get(String key, Class<T> type) {
        return cast(template().opsForValue().get(requireKey(key)), type);
    }

    /** {@link #get(String)} 的别名，保留工具类常见调用习惯。 */
    public static <T> T getKey(String key) {
        return get(key);
    }

    /** {@link #get(String, Class)} 的别名。 */
    public static <T> T getKey(String key, Class<T> type) {
        return get(key, type);
    }

    /**
     * 写入值且不设置过期时间。只有确实需要常驻的键才使用；业务缓存应优先使用带 TTL 的重载。
     */
    public static <T> void set(String key, T value) {
        template().opsForValue().set(requireKey(key), requireValue(value));
    }

    /**
     * 写入值并设置过期时间；TTL 必须是正数。
     */
    public static <T> void set(String key, T value, Duration ttl) {
        template().opsForValue().set(requireKey(key), requireValue(value), requireTtl(ttl));
    }

    /**
     * 仅当 key 不存在时写入，并设置过期时间；SETNX 与 TTL 由 Redis 原子完成。
     */
    public static <T> boolean setIfAbsent(String key, T value, Duration ttl) {
        Boolean written = template().opsForValue()
                .setIfAbsent(requireKey(key), requireValue(value), requireTtl(ttl));
        return Boolean.TRUE.equals(written);
    }

    public static boolean delete(String key) {
        return Boolean.TRUE.equals(template().delete(requireKey(key)));
    }

    public static long delete(Collection<String> keys) {
        Objects.requireNonNull(keys, "keys 不能为空");
        if (keys.isEmpty()) {
            return 0L;
        }
        keys.forEach(RedisUtil::requireKey);
        Long deleted = template().delete(keys);
        return deleted == null ? 0L : deleted;
    }

    public static boolean hasKey(String key) {
        return Boolean.TRUE.equals(template().hasKey(requireKey(key)));
    }

    public static boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(template().expire(requireKey(key), requireTtl(ttl)));
    }

    /**
     * 返回剩余过期时间（秒）：-1 表示没有过期时间，-2 表示 key 不存在。
     */
    public static long getExpire(String key) {
        Long seconds = template().getExpire(requireKey(key));
        return seconds == null ? -2L : seconds;
    }

    public static <T> void hSet(String key, String field, T value) {
        template().opsForHash().put(
                requireKey(key), requireField(field), requireValue(value));
    }

    @SuppressWarnings("unchecked")
    public static <T> T hGet(String key, String field) {
        return (T) template().opsForHash().get(requireKey(key), requireField(field));
    }

    public static <T> T hGet(String key, String field, Class<T> type) {
        return cast(template().opsForHash().get(requireKey(key), requireField(field)), type);
    }

    /**
     * 读取整个 Hash；字段名统一转成 String，值保持反序列化后的类型。
     */
    public static Map<String, Object> hGetAll(String key) {
        Map<Object, Object> entries = template().opsForHash().entries(requireKey(key));
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(entries.size());
        entries.forEach((field, value) -> result.put(String.valueOf(field), value));
        return result;
    }

    /**
     * 读取整个 Hash 并按目标类型转换每个值。
     */
    public static <T> Map<String, T> hGetAll(String key, Class<T> type) {
        Objects.requireNonNull(type, "type 不能为空");
        Map<Object, Object> entries = template().opsForHash().entries(requireKey(key));
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        Map<String, T> result = new LinkedHashMap<>(entries.size());
        entries.forEach((field, value) -> result.put(String.valueOf(field), cast(value, type)));
        return result;
    }

    public static boolean hHasKey(String key, String field) {
        return Boolean.TRUE.equals(template().opsForHash()
                .hasKey(requireKey(key), requireField(field)));
    }

    public static long hDelete(String key, String... fields) {
        Objects.requireNonNull(fields, "fields 不能为空");
        if (fields.length == 0) {
            return 0L;
        }
        for (String field : fields) {
            requireField(field);
        }
        Long deleted = template().opsForHash().delete(requireKey(key), (Object[]) fields);
        return deleted == null ? 0L : deleted;
    }

    public static long sAdd(String key, Object... values) {
        Objects.requireNonNull(values, "values 不能为空");
        if (values.length == 0) {
            return 0L;
        }
        for (Object value : values) {
            requireValue(value);
        }
        Long added = template().opsForSet().add(requireKey(key), values);
        return added == null ? 0L : added;
    }

    /**
     * 读取 Set；返回集合的元素类型由调用方泛型目标推断。
     */
    @SuppressWarnings("unchecked")
    public static <T> Set<T> sMembers(String key) {
        Set<Object> members = template().opsForSet().members(requireKey(key));
        return members == null ? Set.of() : (Set<T>) (Set<?>) members;
    }

    public static long sRemove(String key, Object... values) {
        Objects.requireNonNull(values, "values 不能为空");
        if (values.length == 0) {
            return 0L;
        }
        for (Object value : values) {
            requireValue(value);
        }
        Long removed = template().opsForSet().remove(requireKey(key), (Object[]) values);
        return removed == null ? 0L : removed;
    }

    public static boolean sHasKey(String key, Object value) {
        return Boolean.TRUE.equals(template().opsForSet()
                .isMember(requireKey(key), requireValue(value)));
    }

    public static <T> long lPush(String key, T value) {
        Long size = template().opsForList().leftPush(requireKey(key), requireValue(value));
        return size == null ? 0L : size;
    }

    /**
     * 读取列表区间；索引语义与 Redis 一致，允许负数（-1 表示最后一个元素）。
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> lRange(String key, long start, long end) {
        List<Object> values = template().opsForList().range(requireKey(key), start, end);
        return values == null ? List.of() : (List<T>) (List<?>) values;
    }

    public static void lTrim(String key, long start, long end) {
        template().opsForList().trim(requireKey(key), start, end);
    }

    private static <T> T cast(Object value, Class<T> type) {
        Objects.requireNonNull(type, "type 不能为空");
        return value == null ? null : type.cast(value);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Redis key 不能为空");
        }
        return key;
    }

    private static String requireField(String field) {
        // Hash 字段允许空串，但 null 无法序列化，必须在这里拒绝。
        return Objects.requireNonNull(field, "Hash field 不能为空");
    }

    private static <T> T requireValue(T value) {
        return Objects.requireNonNull(value, "Redis value 不能为空");
    }

    private static Duration requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Redis TTL 必须为正数");
        }
        return ttl;
    }
}
