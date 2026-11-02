# identity 登录限速迁移（Issue #12 阶段 2）

日期：2026-11-02｜状态：已实现并完成真实 MySQL/Nacos/Redis 启动与 HTTP 验证；按维护者决定不加 fail-open、运行时脚本替换或代码级键增长硬上限，键增长以运维前置条件收口｜关联：[Issue #12](https://github.com/modakai/CloudProcure/issues/12)、[限流公共模块](18-限流公共模块.md)、[验证记录](evidence/issue-12-verification.md)

identity 的表单登录限速已从进程内 Map 迁移到 `common-rate-limit` 的 Redis 程序化 API。账号与来源两个维度由 Redis 单键 Lua 原子计数，多实例共享；identity 重启后未过期额度仍然有效。Redis 不可用、超时、损坏计数会停止认证并返回 503，不回退内存、不无限放行。

## 1. 变更

- `services/identity-service/pom.xml`：新增 `common-rate-limit` 与 `spring-boot-starter-data-redis`。后者显式声明 Redis 客户端，连接仍使用 `spring.data.redis.*`。
- `LoginAttemptLimiter`：删除本地 Map、`Window`、时钟、`synchronized`、SHA-256 账号键和 `max-entries`；改为 `RateLimiter` 的薄封装，保留 `reserve(loginName, source)` 接口（0 允许、正数等待秒数、`RateLimitUnavailableException` 表示依赖故障）。
- 账号先做一次固定长度 SHA-256，再交给模块：避免带换行或超长用户名触发模块的键校验异常，从而绕过账号维度计数；大小写与 trim 语义不变。模块写 Redis 前还会再摘要一次。
- `LoginAttemptFilter`：在密码认证前调用限速器；429 带正数 `Retry-After`，503 使用 `DEPENDENCY_UNAVAILABLE` Problem JSON，均不进入账号查询/BCrypt，不依赖 MVC 全局异常处理器。
- `IdentitySecurityConfiguration`：限速器改为注入程序化 `RateLimiter`；规则仍是账号 5 次/5 分钟、来源 30 次/1 分钟，算法显式固定为 `FIXED_WINDOW`。
- 配置：`application-local.example.yml` 增加 Redis 与 `cloudprocure.rate-limit`；删除 `identity.login-rate-limit.max-entries`。本机 `application-local.yml` 同样配置但被 Git 忽略，Redis 密码只从 `REDIS_PASSWORD` 环境读取。
- 未修改 #4 的数据库 schema、密码长度决策、OIDC 协议链或登录页。

实际 Redis 键（本机）：

```text
cp:local:identity-service:rate-limit:v1:identity:login:account:fixed-window:<digest>
cp:local:identity-service:rate-limit:v1:identity:login:source:fixed-window:<digest>
```

与 issue 示例 `cp:<env>:identity:login:v1:...` 不完全相同：通用模块统一使用 `cp:<env>:<app>:rate-limit:v1:<namespace>:<dimension>:<algorithm>:<digest>`，并强制把算法段放进键，避免切换算法时 WRONGTYPE。业务代码不手工拼键，使用 `RateLimitKey(namespace, dimension, identifier)`。

## 2. 配置

本机启动必须同时提供环境变量（Nacos 配置提供数据库、签名密钥和 bootstrap 账号；Redis 密码由进程环境提供）：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}
      timeout: 2s

cloudprocure:
  rate-limit:
    environment: local
    default-algorithm: FIXED_WINDOW
    log-decisions: true

identity:
  login-rate-limit:
    account-attempts: 5
    account-window: PT5M
    source-attempts: 30
    source-window: PT1M
```

- `REDIS_PASSWORD` 未设置时应用启动失败；不提供无密码回退。
- `spring.data.redis.timeout: 2s` 让 Redis 故障在可接受时间内 fail-closed。
- `identity.login-rate-limit.*` 的规则变化必须全实例一致；规则变化不会清理旧键，未过期旧额度仍按旧键 TTL 生效。
- `max-entries` 已删除；键增长由 TTL 和 Redis 容量策略约束，不再有本地容量淘汰。
- 生产高流量环境应关闭 `log-decisions`，避免每个登录请求写一行 INFO/WARN。

## 3. 顺序与失败语义

1. 先申请来源额度。
2. 来源拒绝：直接返回 429，不创建账号计数。
3. 来源允许：再申请账号额度。
4. 账号拒绝：返回 429；来源额度已经被消耗（保守多消耗）。
5. 任一步 Redis 故障/超时/损坏：抛 `RateLimitUnavailableException`，过滤器返回 503，停止认证；不重试、不退款。
6. 成功登录不清空任一计数；拒绝不递增、不刷新固定窗口 TTL。

两个维度是两次独立脚本，不是跨键事务；中途故障会保守多消耗，但不会放行。请求仍按固定窗口从首次获准开始计时。

## 4. 验证证据

环境：Docker `cloud-mysql`、`cloud-nacos`、`cloud-redis`（Redis 7.4.7），identity 端口 9000/9001，本机 bootstrap 账号，Redis 密码只从环境读取。

- 启动日志确认：
  - `common-rate-limit 已启用：basePrefix=cp:local:identity-service:rate-limit:v1:, defaultAlgorithm=fixed-window, ...`
  - `IdentityApplication` 正常启动，健康检查 200。
- 缺 CSRF token 的 POST `/login`：403，且没有创建/递增登录限速键。
- 同账号 2 次错误密码后成功登录（302 跳到 `/identity/api/v1/me`）：Redis 账号/来源计数均为 3，证明成功登录不清空额度。
- 再 2 次错误密码后第 6 次：HTTP 429、`Retry-After: 299`、Problem JSON `TOO_MANY_REQUESTS`，响应不含用户名；账号键 `value=5`、来源键 `value=5`，第 6 次拒绝没有把账号值加到 6。
- 重启 identity 后立即用同账号登录：仍返回 429，`Retry-After: 257`，证明未过期额度不因进程重启丢失。
- 两个 identity 实例（9000/9001）共享：3 次在 9000、2 次在 9001，第 6 次在 9000 返回 429，证明不是各自获得完整配额。
- 暂停 Redis 容器后登录：HTTP 503、`DEPENDENCY_UNAVAILABLE` Problem JSON，无 `Retry-After`，不进入认证；恢复 Redis 后 `PING` 正常。
- 用错误的 `REDIS_PASSWORD` 启动一个实例：端口仍可监听（Redis 连接是惰性的），但 actuator health 为 DOWN；登录返回同样的 503。认证失败与超时走同一 fail-closed 路径。
- 用户名含换行、5000 字符用户名：均返回 302 失败，不是 500；说明账号预摘要没有把异常输入变成限流绕过。
- `mvn -B -ntp -pl services/identity-service -am test`（`REDIS_INTEGRATION=true`，连接 Docker Redis）：
  - common-rate-limit：48 个测试全部通过（含独立 Redis 的 NOSCRIPT/EVAL 回退）；
  - identity：58 个测试，55 通过、3 个 `IdTokenValidationTest` 因缺少 OIDC_TEST_* 输入跳过，0 失败；其中 `OidcProtocolHttpTest` 15/15 通过，`LoginAttemptLimiterRedisIntegrationTest` 6/6 通过（含账号/来源两个维度并发与窗口过期恢复）。
  - 合计 106 个测试，0 失败、0 错误、3 跳过。
- 测试进程退出时 Surefire 因 Nacos 非守护线程延迟约 30 秒后自终止，日志出现 `Surefire is going to kill self fork JVM`；JUnit 结果仍为成功，这是构建进程退出问题，不是测试失败。

测试源码仍按仓库约定只保留在 Git 忽略的 `src/test/`，干净克隆不会执行这些私有测试。

## 5. 已知边界

- Redis Cluster/Sentinel、故障转移和跨槽事务未验证；当前脚本都是单键。
- 按维护者决定，不新增代码级“新建键硬上限”：硬上限需要跨键原子检查或额外 RTT，且可能被攻击者用来拒绝所有新用户。键增长改为**上线前的运维前置条件**，见下一节；未落实前不能声称风险已消除。
- Redis 的 `maxmemory` 淘汰策略可能提前删除限流键，等于重置额度；限流实例应使用 `noeviction` 或与缓存隔离。
- Redis 重启/AOF 不是零丢失承诺；未过期计数仍可能丢失。
- 来源仍使用 `request.getRemoteAddr()`，不信任 `Forwarded`/`X-Forwarded-For`。代理部署需要单独的入口信任模型；共享 NAT 出口会共享来源额度。
- 没有验证码、永久锁定、自动解封、跨维度原子事务或生产容量；这些仍在范围外。
- 从内存切换到 Redis 时存在一次性额度重置：旧进程内计数不会迁移；本机验证前已清理 `cp:local:identity-service:rate-limit:v1:identity:login:*` 测试键，生产迁移也必须记录这个窗口。
- 变更已提交到 `master`，Issue #12 以 `completed` 关闭；测试源码仍只保留本地。

## 6. 键增长与容量前置条件（上线门槛）

这些不是可选的“建议”，而是 identity 登录限速投入使用的条件：

1. **淘汰策略**：限流键所在 Redis 必须使用 `maxmemory-policy=noeviction`，或使用与缓存隔离的独立实例。不得擅自修改共享 Redis 的全局淘汰策略；需要变更时由实例所有者审批。
2. **容量预算**：按“账号/来源两个维度 × 最大活跃来源数 × 5 分钟/1 分钟 TTL × 键开销”估算最坏键数；账号与来源都可能在 TTL 内被大量随机值创建。容量规划必须包含 Redis 内存、连接数和持久化开销。
3. **外部监控**：至少监控 `INFO keyspace` 的键数、`DBSIZE`、`INFO memory` 的 `used_memory`，以及 `INFO stats` 的 `evicted_keys`（必须为 0；非 0 说明额度可能被提前清除）。按业务前缀统计时使用 `SCAN MATCH ... COUNT ...`，不要在生产使用 `KEYS`。
4. **告警**：键数/内存增长速率异常、`evicted_keys > 0`、Redis 断连、命令超时都应告警。日志中的限流键拒绝/503 也应进入告警，而不是只看应用健康检查。
5. **扩容/清理**：扩容前先确认当前 `maxmemory` 与淘汰策略；清理只能按已知业务前缀和 TTL 处理，禁止 `FLUSHDB`/`FLUSHALL`。实例重启或故障转移后要重新核对计数基准。

本机验证示例（只读检查）：

```powershell
docker exec -e REDISCLI_AUTH="$env:REDIS_PASSWORD" cloud-redis redis-cli CONFIG GET maxmemory-policy
docker exec -e REDISCLI_AUTH="$env:REDIS_PASSWORD" cloud-redis redis-cli INFO keyspace
docker exec -e REDISCLI_AUTH="$env:REDIS_PASSWORD" cloud-redis redis-cli INFO stats | Select-String evicted_keys
docker exec -e REDISCLI_AUTH="$env:REDIS_PASSWORD" cloud-redis redis-cli --scan --pattern 'cp:local:identity-service:rate-limit:v1:*' | Measure-Object
```

## 7. 启动方式

```powershell
# 构建（公共库版本由 BOM 管理）
mvn -B -ntp -pl services/identity-service -am -DskipTests package

# 启动；数据库/签名密钥/bootstrap 账号由 Nacos 配置提供
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:REDIS_PASSWORD = '<本机 Redis 密码>'
java -Dfile.encoding=UTF-8 -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar
```

验证请求需要先 GET `/login` 取 CSRF，再 POST 用户名/密码；默认额度耗尽后返回 429。Redis 故障时返回 503。验证完成后应停止实例并清理本次测试前缀的限流键。
