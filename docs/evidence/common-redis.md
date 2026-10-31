# common-redis 缓存模块验证

日期：2026-10-31（Asia/Shanghai）。对应任务：[GitHub Issue #14](https://github.com/modakai/CloudProcure/issues/14)。改动与私有测试位于本地工作区，尚未提交。

环境：Windows、BellSoft JDK 21.0.10、Maven 3.9.9、本机 Docker Redis 7.4.7（127.0.0.1:6379，启用密码）。测试密码从本机环境读取，不写入仓库，也不出现在本文。

## 执行记录

| 命令 | 结果与范围 |
|---|---|
| `mvn -B -ntp -pl common/common-redis -am verify`（提供 `REDIS_PASSWORD`） | **通过**。13 个单元测试 + 2 个真实 Redis 集成测试，失败 0、错误 0、跳过 0；真实 Redis 用例完成键前缀、TTL、JSON 往返、null 缓存、`RedisUtil` 静态泛型对象/Hash 读写与清理。 |
| `mvn -B -ntp -DskipTests package` | **通过**。根工程及 16 个 reactor 模块全部编译、打包成功，证明 BOM 与 common-redis 不破坏其他服务装配。 |
| `mvn -B -ntp verify`（全量测试，移除 skeleton 后） | **通过**：16 个 reactor 模块全部 SUCCESS；82 项测试中 0 失败、0 错误、24 跳过（实际执行 58 项）。identity 的 `IdentityApplicationTests` 与 access 的真实过滤器链测试已实际执行并通过。跳过项均为需要本机真实前置条件的私有实验，原因见下。 |

## 关键断言

- 序列化：record、enum、数组、`List.of`、`Map.of`、`Set.of`、普通 `ArrayList`/`LinkedHashMap` 与 Spring `NullValue` 均可往返；`List.of` 在根值位置复制为 `ArrayList` 后不再触发 Jackson 的包级私有实现错误。
- 类型白名单：`{"@class":"java.io.File",...}` 反序列化抛出 `SerializationException`；`com.cloudprocure.`、`java.util.`、`java.time.`、`java.math.` 与数组可通过。
- 键格式：真实 Redis 中键为 `cp:it:<uuid>:cache:item::1`，证明应用前缀与缓存名分隔生效。
- TTL：配置 30s 基础 TTL + 5s 抖动，`TTL` 实测落在 28～35s；零 TTL 在属性校验阶段被拒绝。
- null：`cache-null-values=true` 时 Redis 中存在 `...:item::null` 键，`Cache.get(...).get()` 返回 null，说明哨兵可被 `RedisCache` 识别。
- Boot 覆盖：测试中故意设置 `spring.cache.redis.time-to-live=1h`、`key-prefix=wrong:`、`cache-null-values=true`，实际 CacheManager 仍使用本模块的 2m/10s 抖动、`cp:test-service:cache:`、不缓存 null。
- 模板：`redisTemplate` 的键序列化器为 `StringRedisSerializer`，值序列化器为模块的 JSON 包装器，未回退到 JDK 序列化。
- RedisUtil：真实 Redis 中验证了静态泛型 `get`/`set`、`setIfAbsent`、`getExpire`、Hash 读写与批量删除；单元测试覆盖泛型转换、未初始化时的明确失败、参数校验、TTL 拒绝和模板委托。未提供 `INCR`、分布式锁或 `KEYS`/`SCAN` 封装。

## 限制

- 测试源码按维护者既有约定只保留在被忽略的 `src/test/`，公开仓库干净克隆的 `verify` 不会复现这 9 项测试。
- 只验证单节点 Redis；未验证 Cluster/Sentinel 键槽、故障转移、认证失败、超时、网络中断或 `maxmemory` 淘汰。
- 未做并发压测、容量、命中率、跨服务共享和租户隔离验证；这些不能从本记录外推。
- 未验证 Spring Session、identity 登录限流或其他 Redis 用法；它们不属于本模块。
- 全量 `verify` 已通过，但仍有 24 项按前置条件跳过，不能把“跳过”当作已验证：identity 的 `OidcProtocolHttpTest`（15 项）要求本机 9000 端口有运行中的 identity，`IdTokenValidationTest`（3 项）和 `ClientSecretDiagnosticTest` 的 HTTP 用例（1 项）要求 `.local/oidc-code-smoke.ps1` 导出的 `OIDC_TEST_*` 输入；access 的 `RealIdentityTokenHttpTest`（3 项）要求真实 identity 签发的 Token；真实 Redis 用例（2 项）要求 `REDIS_PASSWORD`。前置条件满足时这些测试会照常执行，跳过原因写入 Surefire 报告。
