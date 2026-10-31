# 分组父工程与公共库BOM验证

日期：2026-10-31（Asia/Shanghai）。对应任务：[GitHub Issue #13](https://github.com/modakai/CloudProcure/issues/13)。分支`refactor/unified-parent-bom`，决策见[ADR-0003](../adr/0003-unified-parent-and-common-bom.md)。

环境：Windows、BellSoft JDK 21.0.10、Maven 3.9.9；依赖版本沿用根POM的Boot 4.0.8、Cloud 2025.1.3、SCA 2025.1.0.0，未升级任何第三方版本。

## 执行记录

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -DskipTests clean verify` | 16/16 reactor项目SUCCESS：根、两个分组父工程、common-bom、4个公共库、7个服务与gateway；全量重新打包。 |
| `mvn -B -ntp -pl common/common-webmvc,gateway -am verify` | BUILD SUCCESS：common-http 4项、common-webmvc 3项、gateway 3项测试通过。 |
| `mvn -B -ntp -pl services/identity-service -am '-Dtest=LoginAttemptLimiterTest,LoginRateLimitSecurityTest,IdentitySecurityConfigurationTest,CredentialSnapshotTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 20项测试，失败0、错误0、跳过0，BUILD SUCCESS。 |
| `mvn -B -ntp -DskipTests install` | SUCCESS，父POM、common-bom与公共库写入本机仓库。 |
| `mvn -B -ntp -o -f services/access-service/pom.xml validate` | BUILD SUCCESS，证明install后单个服务POM可独立解析BOM。 |
| `mvn -B -ntp -pl services/access-service dependency:list '-DincludeGroupIds=com.cloudprocure'` | common-http、common-mapping、common-webmvc均为`0.1.0-SNAPSHOT`，由BOM提供版本。 |
| `mvn -B -ntp -pl services/access-service -am test '-Dtest=!RealIdentityTokenHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false'` | 5项测试通过；排除的类需要真实JWK Set端点。 |
| `mvn -B -ntp verify` | **FAILURE**：identity执行40项后出现4项失败、6项错误，构建未进入后续服务。 |
| `mvn -B -ntp -pl services/access-service -am verify` | **FAILURE**：仅`RealIdentityTokenHttpTest` 3项错误，其余5项通过。 |

## 未通过项与原因

- `SigningKeyConfigurationTest`：需要`IDENTITY_SIGNING_KEYSTORE`与`IDENTITY_SIGNING_KEYSTORE_PASSWORD`，缺失时测试自身抛出“请在测试运行配置中设置密钥文件路径”。
- `IdTokenValidationTest`：需要先运行协议实验脚本并取得输入，缺失时抛出“请先在同一终端运行协议实验脚本”。
- `ClientSecretDiagnosticTest`、`OidcProtocolHttpTest`、`IdentityApplicationTests`、`SkeletonHttpTest`：需要运行中的identity及其local数据库、Nacos、签名密钥配置；前两项直接表现为连接失败。
- access的`RealIdentityTokenHttpTest`：使用`resource-server-test`配置，需要真实JWK Set端点；缺失时上下文在`jwtDecoderByJwkKeySetUri`条件处失败。
- 这些前置条件在[协议测试记录](M0-issue-4-protocol.md)中已写明为手工运行要求。失败与本次父工程/BOM调整没有因果关系，但默认`mvn -B -ntp verify`在本机不是可全绿的门禁，不能用`-DskipTests`的通过替代测试结论。

## 结构与依赖检查

- 服务POM与gateway不再出现`common-*`依赖的`${project.version}`；gateway只保留BOM导入自身的版本声明。
- `common-webmvc`依赖`common-http`仍写`${project.version}`：`common`父工程不能导入自己的子BOM，否则依赖管理成环。
- 可执行JAR内容回归（`jar tf`统计）：

| 模块 | Lombok条目 | 公共库JAR数 | 说明 |
|---|---:|---:|---|
| gateway | 0 | 1 | 只含common-http；不含common-webmvc、spring-webmvc、spring-data-jpa、hibernate-core、tomcat-embed-core |
| identity-service | 0 | 3 | common-webmvc、common-mapping、common-http |
| access-service | 0 | 3 | common-webmvc、common-mapping、common-http |
| catalog/trade/inventory/payment/fulfillment | 0 | 2 | common-webmvc、common-http |

## 限制

- 未验证IDE重新导入、私服发布或消费者独立仓库引用；BOM当前只服务本仓库。
- 未新增MySQL、Nacos、Redis集成用例；本次只覆盖构建结构与依赖解析。
- 测试源码仍按仓库约定只保留在被忽略的`src/test/`，干净克隆无法复现上述测试数字。
