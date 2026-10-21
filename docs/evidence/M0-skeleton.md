# M0骨架验证记录

日期：2026-10-20（Asia/Shanghai）。这是原始骨架的历史记录，原执行未记录Git提交号。它不证明当前提交包含当时的Wrapper或测试源码，也不代表整个M0完成。2026-10-21起采用本地Maven且测试源码不上传GitHub，当前执行结果以[#2验证报告](M0-issue-2/README.md)为准；下列旧命令仅保留作历史记录，不再作为操作指南。

## 环境

- Windows 11 amd64，BellSoft OpenJDK21.0.10。
- Maven3.9.9；Wrapper3.3.4（only-script）固定下载Maven3.9.9。
- Boot4.0.8、Cloud2025.1.3、SCA2025.1.0.0。
- 可执行JAR核对：Security7.0.7、Spring Data JPA4.0.7、Hibernate7.2.24.Final、Gateway5.0.3、Nacos client3.1.1、Sentinel1.8.9。

## 执行结果

| 检查 | 结果 | 证据与限制 |
|---|---|---|
| 根工程`mvn -B -ntp verify` | 通过 | 根聚合及八应用均SUCCESS；首次完整构建02:19，含依赖下载，不作为性能基准 |
| 最终`.\mvnw.cmd -B -ntp verify` | 通过 | 八应用均SUCCESS；9项测试全部通过，01:03完成（22:03:12）；含资源服务专项，不作为性能基准 |
| 八应用HTTP测试 | 8项通过，0失败、0错误、0跳过 | 每模块真实随机端口，skeleton配置；详见target/surefire-reports |
| 资源服务JWT专项 | 1项通过 | access的local过滤器，独立测试配置；真实HTTP取公钥，正确Token=200，缺失/错误签名/issuer/受众/过期=401；Wrapper模块verify通过 |
| 健康与访问边界 | 通过 | health=200且UP；未配置业务路径/敏感管理路径=401；identity发现端点=401 |
| 可执行JAR | 通过 | 八个JAR均含Boot JarLauncher；Gateway、identity另以java -jar默认profile实际启动并验证200/401 |
| 网关技术边界 | 通过 | 打包依赖不含spring-webmvc、spring-data-jpa、hibernate-core |
| Wrapper | 通过 | `.\mvnw.cmd -version`报告Maven3.9.9及JDK21 |
| Compose | 静态通过 | 使用临时占位变量运行config --quiet，不输出密码；真实环境需自行配置凭据 |
| 镜像坐标 | 公开registry查询通过 | MySQL8.4.7、Redis7.4.7、Nacosv3.1.2 tag与manifest digest核对；未拉取/运行或扫描 |

Surefire日志有测试工具动态加载Agent的JDK提示；本次无测试失败，后续升级测试工具时按工具官方建议处理。本次未为消除提示关闭JDK检查或业务安全校验。

## 未验证

Docker Desktop Linux引擎未运行；尚未进行真实MySQL/JPA/Flyway、Nacos注册配置、Redis会话、认证授权协议、mTLS、RocketMQ或Sentinel规则治理联调，亦未进行漏洞与容量验证。不得将本记录解释为所有版本门禁已通过。
