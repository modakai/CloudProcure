# 公共HTTP基础代码验证

日期：2026-10-24（Asia/Shanghai）。对应任务：[GitHub Issue #10](https://github.com/modakai/CloudProcure/issues/10)。改动和私有测试位于本地工作区，尚未提交。

## 执行记录

环境：Windows、BellSoft JDK 21.0.10、Maven 3.9.9；沿用根POM的Boot 4.0.8依赖管理。

| 命令 | 结果与范围 |
|---|---|
| `mvn -B -ntp verify` | 全聚合通过：父POM、两个公共库、八应用。全工程回归包含真实JWT签名、issuer、audience、过期校验及安全默认拒绝。 |
| `mvn -B -ntp -pl common/common-webmvc,gateway -am verify` | 追加返回值校验和MDC恢复测试后，公共库与网关复验通过。 |
| `mvn -B -ntp -pl common/common-webmvc,gateway -am install` | 补充NOOP Tracer回退边界后，受影响模块带测试重新构建、安装通过；可用于单模块run。 |
| `mvn -B -ntp -DskipTests package` | 最终源码下重新打包全部应用，确保独立JAR包含最新公共库；此步骤不重复计为测试通过。 |

最新Surefire报告共26项测试，失败0、错误0、跳过0。统计来自本地各模块`target/surefire-reports/TEST-*.xml`；这是上述全量回归与后续受影响模块复验的报告汇总，不是公开仓库可独立复现的测试套件。

| 模块 | 测试项 |
|---|---:|
| common-http | 4 |
| common-webmvc | 3 |
| gateway | 3 |
| identity-service | 2 |
| access-service | 4 |
| catalog/trade/inventory/payment/fulfillment | 各2，共10 |

## 关键断言

- 可选Result与直接DTO分别返回；204无正文。
- MVC真实HTTP覆盖JSON解析、请求体与方法参数校验、分页默认值/越界/类型绑定、404/405/409/500；405保留Allow头。
- 类型绑定错误不回显输入原文；未知异常不回显SQL、堆栈或查询参数；返回值校验错误为500且不附加客户端字段违例。
- MVC与WebFlux的401/403和CSRF错误产生Problem Details；实际JWT拒绝保留Bearer挑战头与对应正文。
- 协议400使用真实Bearer EntryPoint和模拟Servlet请求/响应做边界验证，没有为制造400而在生产配置启用查询参数Token。重复Authorization头的400假设经验证不成立，已删除该断言。
- WebFlux本地异常覆盖409/500/503/404，下游已生成的错误正文保持原样；错误正文与本服务X-Trace-Id一致。
- 客户端X-Trace-Id不直接采信；已有span优先；无span及NOOP Tracer回退为独立标识；MVC在下游异常时仍恢复MDC。
- 检查Gateway可执行JAR：包含common-http，不含common-webmvc、spring-webmvc、spring-data-jpa、hibernate-core或tomcat-embed-core。tomcat-embed-el为表达式语言依赖，不是Servlet运行容器。

## 限制

测试源码按维护者既有约定仅保留于被忽略的`src/test/`，不会自动进入Git；从公开仓库干净克隆执行verify可能没有这些测试。未验证MySQL、Nacos、Redis、OIDC协议、生产代理透传或完整分布式追踪。当前本地回退ID仅关联单个请求，不证明跨服务传播。
