# Issue #4：账号与请求来源登录限速

日期：2026-10-24（Asia/Shanghai）。范围：当前单实例 identity 的表单密码登录。

## 已实现行为

| 维度 | 默认额度 | 作用 |
| --- | --- | --- |
| 登录账号 | 5 分钟最多 5 次 | 同一账号换来源仍共享额度 |
| 请求来源 | 1 分钟最多 30 次 | 同一来源换账号仍共享额度 |

每个键从第一次获准尝试时开始独立固定窗口，不按整点分桶。额度以内的请求继续由框架认证；耗尽后的下一次尝试返回 HTTP 429、`Retry-After` 整数秒及现有 Problem JSON，错误码为 `TOO_MANY_REQUESTS`，不暴露账号是否存在、命中的维度或凭据。等待时间向上取整；等待后仍需同时满足两个维度的额度，不能视为保证登录成功的时间。

统计的是所有通过 CSRF 检查的登录尝试，包含成功、错误密码和不存在账号。进入账号查询及 BCrypt 之前原子预占额度，避免仅在失败事件中递增计数造成并发绕过。登录成功不清空任一计数；账号限速拒绝的请求也消耗来源额度。来源已受限时直接拒绝，不为新账号创建计数。

账号键与框架用户名处理一致先 `trim()`，再转为 SHA-256 摘要以限制内存中的键大小；此处摘要仅作为计数键，不作为密码存储方式。限速不查询账号库，不改变账号状态、不创建账号、不修改密码、授权或已有会话。已经建立的会话不需要再次通过密码登录限速。

窗口到期可以重新尝试，拒绝请求不延长窗口。两个维度分别最多保留 10000 个键；满容量时先回收过期键，仍满则拒绝新键并提供最早可重试时间，不淘汰仍有效的计数以避免绕过。计数检查、容量控制及递增由同一临界区保护。

## 接入与配置

- `LoginAttemptLimiter`：两个维度的有界计数及单调时钟窗口。
- `LoginAttemptFilter`：仅匹配 POST `/login`，接在 CSRF 之后、`UsernamePasswordAuthenticationFilter` 之前；不是 Servlet Filter Bean，避免 Boot 注册到安全链外或重复运行。
- `IdentitySecurityConfiguration`：创建限速器并接入登录链；不改 OAuth/OIDC 协议链。
- `application.yml`：显式 `server.forward-headers-strategy: none`，当前直连部署使用连接对端地址。
- `application-local-example.yml`：给出以下候选额度，可在实际配置源覆盖；变更后重启生效。

```yaml
identity:
  login-rate-limit:
    account-attempts: 5
    account-window: PT5M
    source-attempts: 30
    source-window: PT1M
    max-entries: 10000
```

额度、窗口和容量必须为正数，非法配置启动失败。默认值用于当前本地实验，没有经过真实用户负载与共享出口环境调参。

来源使用 `request.getRemoteAddr()`，过滤器不读取 `Forwarded`、`X-Forwarded-For` 等用户可伪造头。未来代理部署必须同时限制服务直连入口、配置可信代理及入口对转发头的清洗，再重新验证地址解析。不能只开启转发头解析就宣称来源可信；若仍保留直连配置，代理后的所有用户会共享代理地址的来源额度。

安全链位置与非 Bean 注册依据：[Spring Security 7 Servlet 架构](https://docs.spring.io/spring-security/reference/7.0/servlet/architecture.html)。

## 实测证据

执行命令：

```powershell
# 使用合成身份和隔离过滤链；不读取真实密码或访问数据库。
mvn -B -ntp -pl services/identity-service -am `
    '-Dtest=LoginAttemptLimiterTest,LoginRateLimitSecurityTest,IdentitySecurityConfigurationTest,CredentialSnapshotTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' `
    test
```

2026-10-24 21:54 执行结果：20 个测试，0 失败、0 错误、0 跳过，BUILD SUCCESS。

| 测试组 | 数量 | 验证内容 |
| --- | ---: | --- |
| LoginAttemptLimiterTest | 9 | 跨来源账号额度、跨账号来源额度、被限账号消耗来源、窗口精确到期、拒绝不延长、秒数向上取整、两个维度容量保护及回收、两种维度各 60 次并发仅 5 次获准、非法配置 |
| LoginRateLimitSecurityTest | 5 | 实际安全过滤链中账号和来源 429、转发头伪造无效、未知账号同等处理、超限不进入账号查询和密码认证、CSRF 先于限速、成功不重置额度、登录页与 JWKS 保持可用 |
| IdentitySecurityConfigurationTest | 5 | 默认登录页、401 Problem、CSRF、两种 Accept 下的 OIDC 保存回跳地址不被开发者工具探测覆盖 |
| CredentialSnapshotTest | 1 | 既有认证凭据版本快照回归 |

本机日志为 `.local/identity/login-rate-limit.log`，JUnit XML 位于 identity 模块 `target/surefire-reports/`。按照仓库规则，测试源码仍仅保留于 Git 忽略的 `src/test/`，没有强制加入版本控制；干净克隆无法仅凭命令复现这些用例。

## 边界与未验证项

- 内存计数只对当前进程有效，重启清空，多实例不共享。部署多实例或要求重启保留额度前，必须使用共享存储原子计数并验证依赖故障策略；当前实现不能作为分布式限速验收结果。
- 测试使用 MockMvc 驱动真实过滤链和合成账号，不是新一轮真实 HTTP + MySQL 端到端验收。未重启用户当前运行的服务；新逻辑需重启后才会在该进程生效。
- 没有代理部署、容量压测或公网抗攻击验收；固定窗口允许临近窗口边界出现短时突发，限速不替代入口连接/流量控制。
- 同一 NAT 出口的用户共享来源额度；攻击者仍能暂时耗尽目标账号额度或填满计数容量，造成合法登录被拒。没有永久账号锁定状态，但持续攻击下不能保证账号及时登录。
- 本轮不新增失败登录审计，不声称消除所有账号枚举侧信道；其他 Issue #4 验收限制仍见[协议报告](M0-issue-4-protocol.md)。
