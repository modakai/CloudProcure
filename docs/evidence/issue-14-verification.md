# Issue #14 验收记录

日期：2026-11-02｜范围：`common/common-redis` 缓存序列化、键命名空间与过期策略｜结论：**验收清单通过；实现沿用 ADR/文档的“同名 `redisTemplate` 退让”语义，issue 原文“同类型 Bean”的措辞差异作为记录项**

环境：JDK 21、Maven 3.9.9、Spring Boot 4.0.8、Docker Redis 7.4.7（密码从环境变量读取）。测试源码按仓库规则保留在 Git 忽略的 `src/test/`。

## 1. 执行结果

```powershell
$env:REDIS_PASSWORD = '<本机密码>'
mvn -B -ntp -pl common/common-redis -am verify
mvn -B -ntp verify
```

| 范围 | 测试 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| common-redis 模块（含 2 个真实 Redis 用例） | 16 | 0 | 0 | 0 |
| 全量 reactor | 145 | 0 | 0 | 38 |

全量 `verify` 的 38 个跳过项：identity OIDC 15、identity Redis 登录限速 6、common-rate-limit 真实 Redis 10、access 真实 Token 3、identity IdToken/ClientSecret 4（合计 38）。这些不是本次 common-redis 验收的失败；common-redis 的 2 个真实 Redis 用例在提供密码后实际执行并通过。

全量构建中 identity 测试退出时出现 Nacos 线程导致的 Surefire 30 秒延迟，但 `BUILD SUCCESS`，JUnit 结果无失败。

## 2. 验收矩阵

| Issue #14 验收项 | 结果 | 证据 |
|---|---|---|
| 仅引入依赖 + `@EnableCaching` 后 Spring Cache 使用 JSON 且条目有有限 TTL | PASS | `RedisAutoConfigurationTest` 的 Boot 上下文装配 + `RedisCacheIntegrationTest` 的真实 TTL 断言 |
| 缓存键以应用命名空间和缓存名分隔，两个服务共用 Redis 不互相覆盖 | PASS | `resolveKeyPrefix` 按 `spring.application.name` 生成 `cp:<app>:cache:`；新增不同应用名前缀测试；真实键 `cp:it:<uuid>:cache:item::1` |
| TTL 抖动落在配置区间；未知缓存使用默认 TTL；不产生永不过期条目 | PASS | 30s+5s 实测 28～35s；未配置缓存用默认 TTL 的单元测试；零/负 TTL 启动校验拒绝 |
| 反序列化只接受 `com.cloudprocure.`、`java.util.`、`java.time.`、`java.math.` 及数组 | PASS | `java.io.File` 载荷被拒绝；record/enum/集合/数组/枚举正向往返；数组组件白名单检查 |
| `mvn verify` 通过；真实 Redis 测试只操作测试前缀并清理 | PASS | 全量 `BUILD SUCCESS`；真实用例使用随机 `cp:it:<uuid>:` 前缀，只删除本轮键，未执行 FLUSHDB/FLUSHALL |
| `RedisTemplate<String,Object>` 覆盖为 JSON 序列化，Hash 值与缓存值同序列化器 | PASS | Boot 上下文测试断言 key/value/hash serializers；应用自定义同名 `redisTemplate` 时按条件退让 |
| `common-redis` 进入 `common-bom`，服务不手写版本 | PASS | `common/common-bom/pom.xml` 已登记；全量 reactor 构建通过 |

## 3. 发现与说明

### 3.1 实现“同类型 Bean 退让”的措辞差异（按当前行为收口）

Issue 目标是“应用自定义同类型 Bean 时退让”。当前实现和 ADR/使用指南使用的是：

```java
@ConditionalOnMissingBean(name = "redisTemplate")
```

即只在**同名 `redisTemplate` Bean** 存在时退让；应用如果声明一个不同名字的 `RedisTemplate<String,Object>`，模块仍会创建自己的 `redisTemplate`。这与文档/ADR 的“同名 Bean 退让”一致，但与 issue 原文“同类型 Bean”存在措辞差异。

按当前行为收口：`@ConditionalOnMissingBean(RedisTemplate.class)` 会连 `StringRedisTemplate` 等其他模板子类型一起退让，可能让应用失去 JSON 对象模板。如未来要按字面改成“任意同类型 Bean”退让，需要先补充“何种自定义模板算同类型”的判定与测试。

### 3.2 全量 verify 跳过项

验收项“`mvn verify` 通过”成立，但 145 项中 38 项按前置条件跳过。不能把跳过当成已验证；本次 common-redis 的 2 项真实 Redis 用例已实际执行，其余跳过项属于 identity/access/rate-limit 的其他任务。

### 3.3 真实 Redis 测试稳定性

`RedisCacheIntegrationTest` 的 `evict` 后立即 `hasKey` 曾出现一次跨连接可见性竞态（evict 与 hasKey 连接不同）；测试已改为 1 秒内轮询删除结果，随后连续 3 次和最终模块 verify 均通过。这是测试稳定性修复，不改变生产缓存语义。

### 3.4 未验证边界

- 只验证单节点 Redis；未验证 Cluster/Sentinel、故障转移、认证失败、超时、网络中断、maxmemory 淘汰。
- 未做并发压测、容量、命中率、跨服务真实共享和租户隔离验证。
- 未验证 Spring Session、identity 登录限流或其他 Redis 用法；它们不属于 common-redis。
- 真实 Redis 测试依赖 `REDIS_PASSWORD`；干净克隆不会执行这些私有测试。

## 4. 结论

Issue #14 的五项验收清单均通过，未发现阻断性缺陷。3.1 的“同类型 vs 同名 Bean”按当前行为收口：实现与 ADR/使用指南一致，issue 原文措辞作为记录项。测试与验证记录已更新到 `docs/evidence/common-redis.md`。
