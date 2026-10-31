# Redis公共模块（common-redis）

日期：2026-10-31｜状态：已实现并通过本地单元测试与单节点真实 Redis 集成测试｜对应任务：[Issue #14](https://github.com/modakai/CloudProcure/issues/14)

本模块把“缓存值怎么序列化、键怎么隔离、条目多久过期”收敛成一份可复用的契约。需要 Redis 的服务只声明依赖并启用 Spring Cache，不再各自复制 RedisTemplate、序列化器和 TTL 配置。

## 1. 范围与不做什么

本模块负责：

- `RedisTemplate<String,Object>` 与 Spring Cache 共用的 JSON 值序列化；
- 缓存键的应用命名空间；
- 默认 TTL、随机抖动和按缓存名覆盖 TTL；
- 是否缓存 null 的显式开关；
- 在应用启用 `@EnableCaching` 且 Boot 选中 Redis 时接管 `CacheManager` 的默认配置。

本模块**不**负责：BFF/identity 会话、登录限流、分布式锁、Redis 服务端 `maxmemory`/淘汰策略、缓存穿透/击穿的通用框架、缓存故障降级、租户键生成。金额、库存等最终事实仍以数据库为准，不因为本模块存在就可以放进低风险缓存（见 ADR-008）。

## 2. 接入

服务通过 `services` 父工程导入的 BOM 取得版本，不手写 `${project.version}`：

```xml
<dependency>
    <groupId>com.cloudprocure</groupId>
    <artifactId>common-redis</artifactId>
</dependency>
```

在应用的配置类上启用 Spring Cache：

```java
@Configuration
@EnableCaching
class CacheConfiguration {
}
```

只引入依赖不会让 `@Cacheable` 生效；`@EnableCaching` 是调用方必须知道并明确选择的行为。应用自己声明名为 `redisTemplate` 的 Bean 或名为 `cloudProcureRedisValueSerializer` 的序列化器时，本模块的对应 Bean 退让。

连接参数仍使用 Boot 的 `spring.data.redis.*`，典型本地配置：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: ${REDIS_PASSWORD}
      timeout: 2s

cloudprocure:
  redis:
    cache:
      default-ttl: 10m
      ttl-jitter: 1m
      cache-null-values: false
      caches:
        product-detail:
          ttl: 5m
        product-list:
          ttl: 2m
```

`spring.cache.type` 不配置时，Boot 在检测到 Redis 连接工厂后选择 Redis；同时存在 Caffeine 等实现时必须显式选择。本模块只通过 `RedisCacheManagerBuilderCustomizer` 修改 Redis 缓存，不会把其他缓存实现强改为 Redis。

## 3. 序列化契约

- 键使用 UTF-8 字符串；缓存键格式为 `<prefix><cacheName>::<key>`。
- 值使用 Jackson 3 的 JSON，类型信息写在 `@class` 属性中。记录、枚举、数组、`List`/`Map`/`Set` 和普通 DTO 都按同一规则处理。
- 反序列化白名单只接受 `com.cloudprocure.`、`java.util.`、`java.time.`、`java.math.` 下的类型以及数组；其他类型会抛出 `SerializationException`。这道白名单降低“Redis 中的任意类名被实例化”的风险，但**不等于**把不可信 Redis 变成安全输入。
- `List.of()`、`Map.of()`、`Set.of()` 和 `Collections.unmodifiableXxx()` 在**根值**位置会被复制成 `ArrayList`/`LinkedHashMap`/`LinkedHashSet` 等公开实现。Jackson 无法为 JDK 包级私有实现写出可再实例化的根类型信息；不复制就会在第一次读缓存时失败。嵌套在 DTO 字段里的集合由字段声明类型处理，不需要调用方改写。
- Spring Cache 的 `NullValue.INSTANCE` 使用固定的 JSON 标记往返，保证 `RedisCache` 仍能按引用识别“已缓存的 null”。默认不缓存 null；只有显式打开 `cache-null-values` 时才会写入该标记。
- 不使用 JDK 原生序列化：它不可读、跨语言不可用，且把类版本变化变成二进制兼容问题。

已知边界：

- DTO 改类名/包名后，旧缓存中的 `@class` 无法还原；应在发布时清理相关缓存或更换 `key-prefix`。TTL 只能让旧数据最终消失，不能避免发布窗口内的反序列化错误。
- 第三方类型不在白名单内，不能直接缓存；需要扩展白名单时改代码并补测试，不能通过配置放开任意类型。
- `Object` 字段里再放 JDK 内部不可变集合仍可能缺少可实例化类型；缓存 DTO 应使用明确的字段类型。
- 自定义比较器的 `SortedSet`/`SortedMap` 往返后不保留比较器，元素需可按自然序比较；缓存值优先使用 `List`/`Map`/`Set`。
- 白名单对允许包内“有副作用的 setter/构造器”不提供完整防护。Redis 必须只在内网可达、启用认证，并与其他租户/环境隔离。

## 4. 过期策略

| 配置 | 默认 | 说明 |
|---|---|---|
| `cloudprocure.redis.cache.default-ttl` | `10m` | 未单列的缓存使用该 TTL；拒绝零/负值，避免写出永久键 |
| `cloudprocure.redis.cache.ttl-jitter` | `1m` | 每次写入随机增加 `0..jitter`，把同批过期摊开；0 表示不抖动 |
| `cloudprocure.redis.cache.caches.<name>.ttl` | 无 | 按缓存名覆盖；当前不支持单独的每缓存抖动 |
| `cloudprocure.redis.cache.cache-null-values` | `false` | 打开后 null 也占一个键和一个 TTL |
| `cloudprocure.redis.cache.key-prefix` | `cp:<spring.application.name>:cache:` | 显式配置时应包含环境标识，且不能含 `* ? [ ]` 或空白 |

TTL 在每次写入时计算，读取不会续期（不是 sliding expiration）。抖动只解决“大量键同时过期”，不能替代：

- `@Cacheable(sync = true)` 对单键并发的保护；
- 对热点键的容量与回源限流；
- 对缓存穿透的显式空值策略或布隆过滤器。

Redis 服务端的 `maxmemory-policy` 仍可能在 TTL 之前淘汰键；本模块不修改服务器配置，也不能把“设置了 TTL”当成容量规划。

## 5. 键、环境与租户

默认前缀只包含应用名：

```
cp:catalog-service:cache:product-detail::12345
```

- 同一 Redis 上不同服务不会覆盖同名缓存。
- **不同环境共用 Redis 时必须显式配置 `key-prefix`**（例如 `cp:local:catalog-service:cache:`）；应用名不包含环境。
- 前缀和缓存名都不包含租户。私有缓存键必须由调用方把租户和数据范围放进 `key`，不能只用 `orderId` 之类的资源 ID；否则企业 A 可能读到企业 B 的缓存。缓存不能替代鉴权。
- `@CacheEvict(allEntries = true)` 通过模式扫描清理；缓存键空间很大时应避免依赖整缓存清理，改为精确失效或版本化前缀。

## 6. RedisTemplate 与 StringRedisTemplate

自动配置提供一个名为 `redisTemplate` 的 `RedisTemplate<String,Object>`：键为 UTF-8 字符串，值和 Hash 值为 JSON。应用自定义同名 Bean 时以应用为准。

- 计数、限流、会话等需要原子字符串/数字操作的场景使用 Boot 的 `StringRedisTemplate`；不要用 JSON 值模板做 `INCR`。
- `RedisTemplate` 是阻塞 API。Gateway 的 WebFlux 事件循环不要调用它；会话与响应式缓存使用各自的响应式设施。
- 本模块不提供 `RedisService` 大包装；直接使用 Spring 的模板与 Cache 抽象，减少一层无行为的转发。

## 7. 故障语义

默认不安装 `CacheErrorHandler`：Redis 连接失败、超时、序列化失败都会向调用方抛出。这样不会把“没读到缓存”伪装成“数据库没有数据”或“请求成功”，但也意味着 Redis 故障会放大为接口失败。

需要“缓存不可用就回源”的服务应自行配置 `CacheErrorHandler`，并逐缓存确认降级是否安全；金额、库存、权限等写入或强一致判断不应静默回源后继续。

## 8. 离线启动与测试

加入 `spring-boot-starter-data-redis` 后，Boot 会注册 Redis 健康检查。没有 Redis 的 `skeleton`/测试环境调用 `/actuator/health` 会得到 DOWN；应在这些 profile 设置 `management.health.redis.enabled=false`，而不是排除 `DataRedisAutoConfiguration`（排除后本模块的模板缺少连接工厂，应用无法启动）。连接本身是延迟建立的，单纯启动不会主动连 Redis。

测试源码按维护者约定只保留在被忽略的 `src/test/`。本地验证包括：

```powershell
# 单元测试；没有 REDIS_PASSWORD 时真实 Redis 用例自动跳过
mvn -B -ntp -pl common/common-redis -am test

# 真实 Redis（密码从本机环境读取，不写入仓库）
$env:REDIS_PASSWORD = '<local-password>'
mvn -B -ntp -pl common/common-redis -am test
```

集成测试使用随机前缀 `cp:it:<uuid>:cache:`，只删除本轮键，不对共享实例执行 `FLUSHDB`/`FLUSHALL`。结果见[验证记录](evidence/common-redis.md)。

## 9. 参考

- [Spring Data Redis：Redis Cache](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html)
- [Spring Boot：Caching](https://docs.spring.io/spring-boot/reference/io/caching.html)
- [Spring Data Redis：Serialization](https://docs.spring.io/spring-data/redis/reference/redis/template.html)
- [Redis：EXPIRE](https://redis.io/docs/latest/commands/expire/)
- [Redis：Key eviction](https://redis.io/docs/latest/develop/reference/eviction/)

实现以项目锁定的 Boot 4.0.8、Spring Data Redis 4.0.7、Jackson 3.1.5 实际编译与测试结果为准。
