# Issue #4：本机真实 OIDC 协议验收

日期：2026-10-24（Asia/Shanghai）。

## 本轮结论

对运行中的 identity 执行真实 HTTP 测试，最终结果为 **15 项通过，0 失败、0 错误、0 跳过**；Surefire 测试耗时约 81.981 秒，Maven `BUILD SUCCESS`。

本报告仅覆盖以下协议测试，不表示 Issue #4 全部完成或生产可用。

## 环境与范围

- identity 地址及预期 issuer：`http://127.0.0.1:9000`。
- 固定客户端：`cloudprocure-portal`，认证方式为 `client_secret_basic`。
- 精确注册回调：`https://portal.cloudprocure.test/login/oauth2/code/portal`。
- 浏览器行为由 JDK HttpClient 和独立 CookieManager 模拟：取得真实登录页的 CSRF Token，然后提交真实账号密码。
- 所有 HTTP 客户端禁用自动重定向；测试解析 identity 返回的回调，不访问门户或非法外部地址。
- 使用已有本机 MySQL；每次成功取得授权码后，通过官方 JDBC 仓储读回授权记录，确认协议记录确已落库。
- 凭据从当前 Spring Environment 读取，测试源码和本报告不包含其值。
- 每个协议用例使用独立授权码；测试结束仅删除自己取得并登记的授权记录，不重置账号、客户端或签名密钥。
- 未停止或重启用户正在运行的 identity 进程，未修改生产认证配置。

## 结果明细

| 用例 | 结果与验证内容 |
|---|---|
| Code + PKCE S256 正向链路 | 通过；兑换得到 Access Token 与 ID Token，校验 ID Token 签名、issuer、受众、时间与本次 nonce；ID Token 带数字凭据版本，UserInfo subject 与其一致 |
| 缺少 challenge | 拒绝；未取得授权码 |
| 缺少 challenge method | 拒绝；未取得授权码 |
| PKCE plain | 拒绝；未取得授权码 |
| 错误 verifier | `400 / invalid_grant` |
| 缺少 verifier | `400 / invalid_grant` |
| 错误客户端 Secret | `401 / invalid_client` |
| 缺少客户端认证 | 拒绝；返回 `invalid_client` |
| 非白名单授权回调 | 本机拒绝，不返回外部 Location |
| 兑换时 redirect 不一致 | `400 / invalid_grant` |
| 授权码重复兑换 | 首次成功，第二次 `400 / invalid_grant`；不继续使用重放用例产生的 Token |
| 授权码过期 | 按数据库中本用例的真实过期时间等待到期后再请求，返回 `400 / invalid_grant`；未修改 TTL 或数据库时间 |
| 错误登录密码 | 回到统一登录错误路径，未建立成功登录跳转 |
| 不存在的登录账号 | 回到统一登录错误路径，未建立成功登录跳转 |
| JWKS | 存在公钥，不包含 RSA 的 `d`、`p`、`q`、`dp`、`dq`、`qi`、`oth` 私钥参数 |

非法授权回调初次实测返回 403，而测试最初限定 400，造成一个过窄的状态码断言失败。当前用例要求返回 400 或 403 且不含 Location；接受 Boot 错误分派被默认拒绝链转换成 403 的行为，没有放宽任何生产访问规则。

错误账号与密码用例只验证拒绝及跳转路径，没有验证完整防枚举、审计或限速能力。

## 本机复现

测试源码：`services/identity-service/src/test/java/com/cloudprocure/identity/client/OidcProtocolHttpTest.java`。

沿用仓库既有约定，`src/test/` 被忽略，测试源码仅保留本机。公开仓库干净克隆不能仅凭本报告自动复现全部测试。

运行前保持 identity 在线，并准备其既有 local 数据库、Nacos、签名密钥等必要配置。测试读取的协议凭据配置键为：

- `OIDC_ISSUER_URI`
- `IDENTITY_BOOTSTRAP_LOGIN`
- `IDENTITY_BOOTSTRAP_PASSWORD`
- `IDENTITY_PORTAL_CLIENT_SECRET`

测试上下文禁用账号和客户端初始化；这些凭据只用于向既有服务发送测试请求。

```powershell
# 使用真实服务和数据库；凭据不通过命令行参数传递。
mvn -B -ntp -pl services/identity-service -am `
    '-Dtest=OidcProtocolHttpTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' `
    test
```

本机结果文件：

- `services/identity-service/target/surefire-reports/TEST-com.cloudprocure.identity.client.OidcProtocolHttpTest.xml`
- `.local/identity/oidc-protocol-http.log`

发布任何日志或报告前仍需脱敏；本报告不复制完整 Token、授权码、Secret、Cookie 或配置正文。

## 尚未完成的验收

- 本轮使用宿主机 HTTP，不覆盖 HTTPS、证书信任及浏览器和容器共享 issuer 的可达性。
- 本轮未验收登录限速；后续已补充单实例账号与来源限速及隔离安全链测试，见[登录限速证据](M0-issue-4-login-rate-limit.md)。尚非真实 HTTP + MySQL、代理或多实例验收。
- 当前初始化代码密码下限为 8 字符，与 Issue #4 的最少 12 字符仍不一致；弱口令拒绝也未完整验收。
- 本轮未做 BCrypt 成本参数的硬件基准测试；后续已独立补测，见 [BCrypt 成本实测](M0-issue-4-bcrypt.md)。生产参数仍需结合目标硬件与并发预算定型。
- 本轮未重跑 skeleton 模式隔离、重启恢复、密钥缺失或轮换测试，不将此前用户反馈视为本轮独立执行证据。
- 本轮没有重新执行 access-service 的实际资源服务器用例；用途分离测试属于上一阶段结果。
- 本轮不覆盖实时撤权、刷新并发、完整退出、MFA、备份恢复或 OIDC 标准认证。

因此，本次协议测试通过后仍不能关闭 Issue #4。
