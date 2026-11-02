# Issue #12 验证记录

日期：2026-11-02｜范围：阶段 1 通用限流 Starter + 阶段 2 identity 登录限速迁移｜结论：**功能验收通过；剩余项按维护者决定作为最终安全/运维约束收口**

验证环境：JDK 21、Maven 3.9.9、Spring Boot 4.0.8、Docker MySQL 8.4 / Nacos 3.1.2 / Redis 7.4.7；identity 以 `local` profile 启动。测试源码按仓库规则保留在 Git 忽略的 `src/test/`；变更已提交到 `master`，Issue #12 以 `completed` 关闭。

## 1. 验证命令

```powershell
# 公共限流模块（含真实 Redis 与独立 Redis NOSCRIPT）
$env:REDIS_PASSWORD = '<本机密码>'
$env:REDIS_INTEGRATION = 'true'
$env:REDIS_NOSCRIPT_PORT = '6381'   # 独立临时 Redis
mvn -B -ntp -pl common/common-rate-limit -am test

# identity + 公共模块完整测试（identity 需要 9000 在线以运行真实 OIDC HTTP 测试）
$env:SPRING_PROFILES_ACTIVE = 'local'
mvn -B -ntp -pl services/identity-service -am test

# 全仓库构建
mvn -B -ntp -DskipTests package
```

结果（只统计 Issue #12 直接相关的两个模块；reactor 还会执行 common-http/common-webmvc 等其他模块测试）：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| common-rate-limit | 48 | 0 | 0 | 0 |
| identity | 58 | 0 | 0 | 3（`IdTokenValidationTest` 缺 OIDC_TEST_* 输入） |
| 合计 | 106 | 0 | 0 | 3 |

全仓库 `package` 成功。identity 测试退出时 Surefire 因 Nacos 非守护线程延迟约 30 秒后自终止，日志有 `Surefire is going to kill self fork JVM`，但 JUnit 结果为成功。

## 2. 阶段 1 验收矩阵

| 验收项 | 结果 | 证据 |
|---|---|---|
| 仅依赖 + 配 Redis 后自动配置生效 | PASS | identity 启动日志 `basePrefix=cp:local:identity-service:rate-limit:v1:`；`RateLimitAutoConfigurationTest` |
| `@RateLimit` + AOP 拦截普通 Bean 方法 | PASS | `RateLimitAspectTest`；类级默认、方法级覆盖、算法属性传递 |
| 程序化 `RateLimiter` | PASS | identity `LoginAttemptFilter` 使用；AOP 与程序化测试 |
| Redis 单键 Lua 原子计数与固定窗口 TTL | PASS | 四个 Lua 脚本；`RedisRateLimiterIntegrationTest` |
| 可配置 key 解析/维度/额度/窗口 | PASS | `RateLimitKey`、`RateLimitKeyResolver`、注解属性、配置元数据 |
| “可配置失败策略” | ACCEPTED（维护者决定） | 固定 fail-closed，不提供 fail-open 开关；这是最终安全取舍，见 `docs/18` |
| “脚本资源位置”配置 | ACCEPTED（维护者决定） | 脚本固定在 `classpath:lua/*.lua`；不开放运行时替换，避免破坏返回契约 |
| 自动配置与配置元数据 | PASS | 生成的 `spring-configuration-metadata.json` + 中文补充元数据 |
| SpEL 求值失败/空 key/非法字符/超长 key 明确失败 | PASS | `RateLimitKeyTest`、`RateLimitKeyExpressionEvaluatorTest` |
| 键包含环境/应用/维度前缀，敏感标识摘要化 | PASS | `DefaultRateLimitKeyResolverTest`；日志不含 identifier |
| 拒绝不递增/不刷新 TTL、Retry-After 为正、过期恢复 | PASS | `RedisRateLimiterIntegrationTest`、固定窗口/令牌桶/漏桶测试 |
| 并发不超发、拒绝不进入被保护方法 | PASS | 四算法并发测试；AOP 拒绝测试 |
| Redis 断连/超时/损坏值/WRONGTYPE/脚本错误 fail-closed | PASS（写入错误未单独注入） | `RedisRateLimiterFailClosedTest`、损坏值/无 TTL/WRONGTYPE 集成测试；identity 真实 503 |
| 自定义 Limiter/Resolver 自动配置退让 | PASS | `RateLimitAutoConfigurationTest`；默认解析器按需创建 |
| AOP self-invocation/private 边界 | PASS | `RateLimitAspectTest`；final 方法仅文档说明 |
| 文档说明 WebFlux/分布式锁/验证码/永久锁定/多键跨槽限制 | PASS | `docs/18-限流公共模块.md` |
| 真实 Redis 测试且只清理测试前缀 | PASS | 随机前缀 + 仅删本轮键；未执行 FLUSHDB/FLUSHALL |
| NOSCRIPT 回退 | PASS | 独立临时 Redis 执行 `SCRIPT FLUSH` 后自动 EVAL 回退，`RedisRateLimiterNoscriptIntegrationTest` |

## 3. 阶段 2 验收矩阵

| 验收项 | 结果 | 证据 |
|---|---|---|
| 两个 identity 实例共享账号/来源额度 | PASS | 9000/9001：3 次+2 次后第 6 次 429 |
| 账号/来源两维度分别并发不超发 | PASS | `LoginAttemptLimiterRedisIntegrationTest` 6/6，含两个维度并发 |
| 成功登录不清空计数 | PASS | 2 次失败+1 次成功，Redis 计数=3；再 2 次失败到 5 |
| 账号拒绝仍消耗来源，来源拒绝不创建账号计数 | PASS | 安全链测试；Redis 集成测试断言无账号键 |
| 首次写入 TTL、拒绝不刷新、过期恢复、Retry-After 向上取整 | PASS | identity Redis 集成测试 + HTTP 429/`Retry-After: 299` |
| 只重启 identity 保留未过期额度 | PASS | 重启后同账号仍 429，`Retry-After: 257` |
| 未知账号相同流程/错误，不泄露账号存在性 | PASS | `LoginRateLimitSecurityTest`、`OidcProtocolHttpTest` |
| Redis 断连/超时/认证失败/损坏值/无 TTL 返回 503 | PASS（写入错误未单独注入） | 暂停 Redis：503 `DEPENDENCY_UNAVAILABLE`；错误 Redis 密码：503；模块损坏值/无 TTL 测试 |
| CSRF 失败不消耗额度 | PASS | 缺 token POST 403，计数不增加 |
| 默认登录页/OIDC 保存回跳/发现/JWKS/Code+PKCE 回归 | PASS | `OidcProtocolHttpTest` 15/15；`IdentitySecurityConfigurationTest` |
| 测试只清理本轮前缀 | PASS | 独立随机前缀；未 FLUSHDB/FLUSHALL |
| 删除失效 `max-entries`，更新配置示例 | PASS | `application-local-example.yml`、`docs/19` |
| Redis 重启/故障转移持久化边界被记录 | PARTIAL | `docs/19` 记录 AOF 非零丢失；未做真实故障转移演练 |
| 键增长/内存预算监控 | ACCEPTED（运维前置条件） | 不新增代码级硬上限；`docs/19` 把 `noeviction`、容量预算、外部监控与告警列为上线门槛，未落实前不宣称生产就绪 |
| Redis Cluster/Sentinel、代理来源、生产容量 | OUT OF SCOPE | 文档明确未验证 |

## 4. HTTP 实测摘要

- 缺 CSRF：403，未产生限速计数。
- 2 次错误密码 + 1 次成功登录：成功跳到 `/identity/api/v1/me`，Redis 账号/来源计数均为 3。
- 再 2 次错误密码：第 6 次 429，`Retry-After: 299`，Problem JSON 为 `TOO_MANY_REQUESTS`，不含用户名；账号值仍为 5。
- 重启 identity：同账号仍 429，`Retry-After: 257`。
- 两实例 3+2：第 6 次 429，`Retry-After: 299`。
- 暂停 Redis：503 `DEPENDENCY_UNAVAILABLE`；错误 Redis 密码：同样 503；恢复后 `PING` 正常。
- 用户名含换行、5000 字符用户名：302 登录失败，不是 500（账号预摘要避免限流绕过）。

## 5. 结论与剩余风险

Issue #12 两阶段的**功能验收路径均通过**，未发现阻断性缺陷。按维护者决定，以下三项作为最终约束收口，不再作为待办：

1. 阶段 1 的失败策略固定 fail-closed、Lua 脚本固定 classpath，不提供运行时开关或脚本替换。
2. 键增长不新增代码级硬上限；`docs/19` 已把 `noeviction`、容量预算、外部监控与告警列为部署前置条件。未满足这些条件前不宣称生产就绪。
3. Redis 写入错误与真实故障转移未逐项注入；代码路径与超时/认证失败同属 `DataAccessException`，但未单独实证。
4. Redis Cluster/Sentinel、代理来源信任、生产容量不在范围内。
5. identity 测试的 Surefire 退出延迟约 30 秒，是 Nacos 线程导致的构建体验问题，不是测试失败。

验证完成后 9000/9001/9002 实例已停止，9000/9001/9002/6381 无监听，identity 限流测试键已清理；Docker `cloud-mysql`、`cloud-nacos`、`cloud-redis` 保持运行。
