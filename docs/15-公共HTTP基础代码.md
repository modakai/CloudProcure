# 公共HTTP基础代码

本次范围由维护者确认：可选Result、游标分页、公共错误码与业务异常、参数校验、全局异常、安全失败响应和请求关联。两个公共模块是普通JAR，不包含业务实体、Repository、通用CRUD、幂等或消息框架。

## 模块与接入

- `common/common-http`：`com.cloudprocure.common.http`中的公共结构、Problem构造和traceId选择。依赖Spring Web通用抽象、Jakarta Validation API和Micrometer Tracing API，不引入Servlet、MVC、JPA或追踪桥。
- `common/common-webmvc`：七个MVC应用依赖此库，通过Boot自动配置装配Advice和关联过滤器，不扩大应用包扫描范围。安全配置显式使用`ProblemSecurityHandlers`，资源服务器使用其Bearer版本。
- Gateway只依赖`common-http`，`ProblemWebHandler`在WebFlux过滤链处理本地异常；下游已经返回的HTTP错误正文直接透传。响应已提交时不尝试重写。

新增依赖后，单模块执行`spring-boot:run`前先在根目录执行`mvn -B -ntp -pl common/common-webmvc -am install -DskipTests`。根目录`mvn -B -ntp verify`会按依赖顺序构建全部模块，独立可执行JAR已包含公共库。

## 返回与请求

`Result.success(data)`、`Result.success()`和`Result.of(code, message, data)`是可选工厂。没有`ResponseBodyAdvice`，也没有与工厂方法重复的ResultUtils；DTO、字符串、文件以及无正文响应保持接口原来的选择。创建资源仍由Controller用`ResponseEntity.created(location)`设置201与Location，异步操作返回202，204不附带Result。

```java
// 接口明确选择包装时才使用Result，HTTP状态仍由Controller决定。
return ResponseEntity.ok(Result.success(dto));
```

列表请求使用`@Valid @ModelAttribute CursorRequest request`，limit缺省20、范围1～100，cursor最长2048字符。公共层不解析游标；所属服务必须校验租户、筛选条件和游标内容，不把分页参数当成鉴权依据。`CursorPage<T>`包含items、nextCursor、hasMore，不附加total；末页用null游标，存在下一页时必须给出非空游标。items保留不可变快照。

业务请求独立定义DTO并添加Jakarta约束，不继承BaseRequest，不从请求体中的userId、tenantId或角色声明建立可信身份。

## 错误契约

所有基础处理器产生`application/problem+json`，字段为type、title、status、detail、instance、code、traceId、violations；type使用`about:blank`，title沿用HTTP状态描述。instance只包含请求路径，避免泄露查询参数。

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "请求参数校验失败",
  "instance": "/api/v1/requisitions",
  "code": "VALIDATION_FAILED",
  "traceId": "9af213ab82bc47fa9e83e66bb907e811",
  "violations": [{"field": "items", "message": "不能为空"}]
}
```

公共码见`CommonErrorCode`。未专门命名的HTTP错误保留原始状态，code使用`HTTP_ERROR`；JSON解析失败、类型不匹配和缺少参数不伪装成业务冲突。返回值校验失败是服务端500，不能归责给客户端。405等框架异常保留Allow等响应头。

业务服务按需实现`ErrorCode`，提供稳定code、错误HTTP状态和允许公开的固定message，再抛出`new BusinessException(code)`。不要把数据库异常、下游响应或用户输入拼进message。未知异常只对外返回固定提示，技术细节写服务端错误日志。violations不提供rejectedValue；类型绑定失败使用固定文案，约束自定义消息也不得插入被校验的原始敏感值。

安全异常发生在Controller之外，因此各应用的安全链显式接入认证失败与拒绝访问处理器；WebFlux的CSRF处理器也显式接入。Bearer版本先委托Spring Security生成挑战头，再写Problem正文：保留框架选定的协议400、失效身份401和权限不足403。当前默认只从认证头读取Token，未启用查询参数或表单Token；不能将框架针对多种Token来源的400规则套到任意重复认证头上。后续OIDC授权、Token、发现/JWKS和登录回调必须使用专用协议链，不把业务Problem处理器接到这些端点。

## 关联与验证边界

优先读取Micrometer当前span的traceId；不存在时生成32位随机十六进制标识。该标识存放于请求属性，写入`X-Trace-Id`和本地错误正文；MVC在请求期间设置并恢复MDC，网关错误日志显式写出标识，不将线程MDC当作响应式上下文。客户端传入的同名头不直接采信。当前未安装追踪桥、采集器或实现跨服务回退ID传播，因此不能宣称全链路追踪已完成。

测试仍遵循项目约定，仅保留于本地被忽略的`src/test/`。本次增加公共结构边界、真实MVC/WebFlux HTTP与Bearer错误正文断言；运行`mvn -B -ntp verify`，报告见各模块`target/surefire-reports`。公开仓库没有这些测试源码，干净克隆的构建成功不等于复现本机测试。中间件、OIDC协议、生产代理转发、完整追踪不在此次验证范围。

本次执行命令、测试数量和边界见[验证记录](evidence/common-http.md)，任务见[Issue #10](https://github.com/modakai/CloudProcure/issues/10)。

## 参考依据

- [Spring Petclinic Microservices](https://github.com/spring-petclinic/spring-petclinic-microservices)：核对Gateway与独立服务的边界，不照搬其注册配置或依赖版本。
- [Petclinic REST异常处理](https://github.com/spring-petclinic/spring-petclinic-rest/blob/master/src/main/java/org/springframework/samples/petclinic/rest/advice/ExceptionControllerAdvice.java)和[技术异常信息泄露记录](https://github.com/spring-petclinic/spring-petclinic-rest/issues/287)：借鉴集中处理结构，同时检查异常消息外泄。
- [Spring MVC错误响应](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)、[WebFlux错误响应](https://docs.spring.io/spring-framework/reference/web/webflux/ann-rest-exceptions.html)：使用内建ProblemDetail与MVC异常分类。

实现以项目锁定的Boot 4 / Spring Framework 7实际编译与运行结果为准，未复制开源项目代码。
