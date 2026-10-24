# Issue #4：BCrypt 成本实测

测量时间：2026-10-24 21:46（Asia/Shanghai）。结果：1 个测试通过，0 失败、0 错误、0 跳过，Maven BUILD SUCCESS。

## 环境与方法

- CPU：Intel Core i5-13400，10 个物理核、16 个逻辑处理器；Windows 11 amd64。
- JVM：BellSoft JDK 21.0.10；Maven 3.9.9；Spring Security 7.0.7。
- 直接调用项目 `PasswordConfiguration.passwordEncoder(cost)`，包含现有 DelegatingPasswordEncoder 与 BCryptPasswordEncoder。
- 随机生成 32 字节并转为 Base64URL 合成密码，不读取账号密码、客户端 Secret、密钥库密码或数据库。
- 成本 10、12、13 依次执行，每组预热 2 轮，再采样 7 轮；每轮串行执行编码、正确密码校验、错误密码校验，并断言校验结果正确。
- 使用 `System.nanoTime()` 计时；单位为毫秒。未控制其他进程负载，不把本轮结果称为并发压测、生产容量或 p95/p99。

## 实测结果

| 成本 | 操作 | 最小值 | 中位数 | 最大值 |
| --- | --- | ---: | ---: | ---: |
| 10 | 编码 | 51.33 | 52.30 | 56.88 |
| 10 | 正确密码校验 | 51.86 | 52.27 | 56.67 |
| 10 | 错误密码校验 | 51.43 | 52.24 | 53.75 |
| 12 | 编码 | 261.74 | 274.26 | 326.59 |
| 12 | 正确密码校验 | 264.19 | 280.56 | 1029.27 |
| 12 | 错误密码校验 | 264.05 | 267.04 | 305.18 |
| 13 | 编码 | 528.45 | 539.28 | 547.92 |
| 13 | 正确密码校验 | 529.59 | 535.91 | 626.53 |
| 13 | 错误密码校验 | 527.49 | 543.40 | 781.20 |

## 配置判断

当前 `IDENTITY_BCRYPT_COST=12` 保持不变，作为本地开发参数继续使用。本机正确密码校验中位数约 281 毫秒；成本 13 约 536 毫秒。提高成本会增加离线猜测的计算开销，也增加正常与错误登录消耗的 CPU 时间，不能仅以越大越好决定参数。

[Spring Security 密码存储指南](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html)建议按目标硬件调节自适应单向函数，使验证约耗时一秒。这是调参指导；本轮成本 12 的中位数尚未达到该参考值，也没有测量成本 14，因此不能称为完成生产参数定型。

成本 12 出现一次 1029.27 毫秒校验，保留原始结果；本次未定位波动原因。7 次采样不足以推导稳定尾延迟。上线前应结合登录限速、目标硬件、并发压力与明确的响应时间预算再决定成本，不能用耗时倒数推断服务 QPS。密码最短长度与 BCrypt 成本是两个独立约束，本次测量不解决弱口令策略。

本轮仅新增本地测试与报告，未改运行配置、现有账号、数据库、生产代码或服务进程。

## 复现

测试源码位于 `services/identity-service/src/test/java/com/cloudprocure/identity/config/BCryptCostBenchmarkTest.java`。按照仓库当前规则，测试目录被 Git 忽略，本次未强制加入版本控制；其他检出不能只靠本报告命令复现，需同时取得该测试源码。

```powershell
# 不需要启动服务、连接数据库或设置真实凭据。
mvn -B -ntp -pl services/identity-service -am `
    '-Dtest=BCryptCostBenchmarkTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' `
    test
```

本机结果文件：

- `.local/identity/bcrypt-cost-benchmark.log`
- `services/identity-service/target/surefire-reports/TEST-com.cloudprocure.identity.config.BCryptCostBenchmarkTest.xml`

剩余验收见 [协议验收报告](M0-issue-4-protocol.md)。此次基准通过不代表 Issue #4 可以关闭。
