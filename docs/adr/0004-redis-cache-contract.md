# common-redis 的缓存序列化与过期契约

状态：accepted｜日期：2026-10-31｜对应决策登记ADR-013，任务[Issue #14](https://github.com/modakai/CloudProcure/issues/14)。

`common-redis` 此前只有占位类。服务若要缓存，只能各自拼装 RedisTemplate、序列化器和 TTL，容易出现四类事故：JDK 二进制值不可读且随类版本变化、不同服务同名缓存互相覆盖、忘记设置 TTL 写成永久键、以及把任意 `@class` 当作可信输入造成反序列化风险。本决策把序列化、键命名空间和过期策略收敛到模块内部，调用方只需要依赖、`@EnableCaching` 和按缓存名配置 TTL。

**决策**

- 值统一使用 Jackson 3 JSON，类型信息写入 `@class`。开启默认类型信息，但用 `PolymorphicTypeValidator` 只允许 `com.cloudprocure.`、`java.util.`、`java.time.`、`java.math.` 与数组；记录和枚举额外显式纳入类型判定，否则 final 的 record 会退化成 `LinkedHashMap`。
- `List.of()`/`Map.of()`/`Set.of()` 等 JDK 内部不可变集合在根值位置复制为公开实现；Spring Cache 的 `NullValue` 用固定 JSON 标记保持单例。原因是 Jackson 无法为包级私有实现写出可再实例化的根类型信息，而缓存接口经常直接返回这些集合。
- 不声明自己的 `CacheManager`，而是注册 Boot 的 `RedisCacheManagerBuilderCustomizer`。只有应用真正启用缓存且 Boot 选中 Redis 时才接管，保留 `spring.cache.type` 在 Redis 与其他缓存实现之间的选择权。
- 缓存默认值从零构建，不继承 Boot 的 JDK 值序列化器与 `spring.cache.redis.*`，只保留 Boot 的统计开关。默认 TTL 10 分钟、抖动 1 分钟、不缓存 null；零/负 TTL 直接拒绝。
- 键前缀默认 `cp:<spring.application.name>:cache:`，允许显式覆盖；应用名只隔离服务，不隔离环境或租户。
- 覆盖 Boot 的 `redisTemplate` 为 String 键 + JSON 值；应用自定义同名 Bean 时退让。

**考虑过的方案**

- 直接使用 Boot 默认：JDK 序列化不可读且跨版本脆弱；全局 TTL 无法按缓存区分；不配 TTL 时写出永久键。拒绝。
- 使用 `GenericJacksonJsonRedisSerializer` 的默认 `NON_FINAL` 类型策略：record 是 final，读取时变成 Map，`@Cacheable` 返回值要转型就会失败。拒绝。
- 引入 Fastjson/Kryo/Protobuf：更快或更紧凑，但增加第二套序列化语义、autoType 或类型注册负担，且可读性不如 JSON。当前不采用。
- 为每个缓存声明固定值类型并生成专用序列化器：类型最安全，但 Spring Cache 接口不携带返回值类型，需要额外注册表或注解，复杂度超出本阶段。保留为未来选项。
- 让应用继续自己配置：本模块就没有存在价值，也无法统一过期和命名空间。

**后果**

- 缓存载荷包含项目类名。改类名/包名会让旧条目反序列化失败；发布时必须清理相关缓存或更换前缀。TTL 只能保证最终消失。
- 白名单需要随缓存类型演进维护；第三方类型默认不能缓存。白名单降低但不消除反序列化风险，Redis 必须内网、认证并与不可信输入隔离。
- 前缀不提供环境或租户隔离；多环境共用一个 Redis 时必须显式配置前缀，私有缓存必须由调用方把租户写进缓存键。
- 抖动不解决单键击穿、缓存穿透或 Redis 服务端淘汰；这些仍需 `sync=true`、空值策略、容量与淘汰策略。
- 默认不降级：Redis 或反序列化失败会抛给调用方。需要回源降级的服务自行配置 `CacheErrorHandler`，并逐缓存确认安全性。
- 验证范围只覆盖单节点真实 Redis 与本机测试；Redis Cluster/Sentinel、跨服务共享、生产容量与故障转移未验证。

重新评估触发条件：需要缓存第三方类型、需要 Protobuf/Kryo 级性能、引入多租户统一键策略、Redis Cluster 的键槽要求、或出现必须静默降级的业务场景。

**2026-10-31 补充**：应维护者要求，自动配置增加静态 `RedisUtil` 泛型门面，覆盖常用对象读写、键生命周期、Hash、Set 与 List 操作，作为 Spring Cache/RedisTemplate 之上的便利层。它不改变上述序列化、命名空间与 TTL 契约，也不是全量命令封装：`INCR`/`DECR`、分布式锁与 `KEYS`/`SCAN` 仍直接使用 `StringRedisTemplate`、Lua 或独立设计。静态模板由自动配置的生命周期 Bean 注入，并在上下文关闭时 compare-and-set 清理；代价是同一 JVM 只能有一个活动模板，多上下文测试不能并发依赖静态门面。
