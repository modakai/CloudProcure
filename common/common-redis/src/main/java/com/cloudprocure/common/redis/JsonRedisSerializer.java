package com.cloudprocure.common.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * 在 Jackson 通用序列化器前做一次“JDK 内部集合”归一化。
 *
 * <p>List.of()/Map.of()/Collections.unmodifiableXxx() 返回包级私有实现，Jackson 在根值
 * 位置无法为它们写出可再实例化的类型信息：{@code List.of(...)} 会序列化成对象而不是数组，
 * 反序列化直接失败。缓存调用方经常直接返回 {@code List.of(...)}，因此写缓存前复制成
 * ArrayList/LinkedHashMap 等公开实现；读缓存时不改写值。</p>
 *
 * <p>只复制 JDK 内部实现，不复制业务 DTO 或用户自定义集合，避免改变自定义集合的语义。</p>
 */
final class JsonRedisSerializer implements RedisSerializer<Object> {

    /**
     * Spring Cache 用 NullValue.INSTANCE 表示“已缓存的 null”，而不是普通 null。
     * 交给 Jackson 反射构造会得到另一个 NullValue 实例，RedisCache 按引用判断时
     * 会把它当成真实值返回；这里用固定 JSON 标记并在读取时还原单例。
     */
    private static final byte[] NULL_VALUE = "{\"@class\":\"org.springframework.cache.support.NullValue\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final RedisSerializer<Object> delegate;

    JsonRedisSerializer(RedisSerializer<Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public byte[] serialize(Object value) throws SerializationException {
        if (value == NullValue.INSTANCE) {
            return NULL_VALUE.clone();
        }
        return delegate.serialize(normalize(value));
    }

    @Override
    public Object deserialize(byte[] bytes) throws SerializationException {
        if (Arrays.equals(bytes, NULL_VALUE)) {
            return NullValue.INSTANCE;
        }
        return delegate.deserialize(bytes);
    }

    private static Object normalize(Object value) {
        if (value == null || !isJdkInternalCollection(value.getClass())) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(element -> copy.add(normalize(element)));
            return copy;
        }
        if (value instanceof SortedSet<?> set) {
            // 保留原比较器，否则大小写不敏感等自定义排序在缓存往返后会变语义。
            @SuppressWarnings({"rawtypes", "unchecked"})
            Set<Object> copy = new TreeSet(set.comparator());
            set.forEach(element -> copy.add(normalize(element)));
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(element -> copy.add(normalize(element)));
            return copy;
        }
        if (value instanceof SortedMap<?, ?> map) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            SortedMap<Object, Object> copy = new TreeMap(map.comparator());
            map.forEach((key, item) -> copy.put(key, normalize(item)));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, normalize(item)));
            return copy;
        }
        return value;
    }

    private static boolean isJdkInternalCollection(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.util.ImmutableCollections$")
                || name.startsWith("java.util.Collections$")
                || name.equals("java.util.Arrays$ArrayList");
    }
}
