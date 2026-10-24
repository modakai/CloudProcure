# Lombok与MapStruct公共模块

`common/common-mapping`是按需引用的普通JAR，聚合Lombok与MapStruct依赖并提供`CommonMapperConfig`，不依赖业务实体、Web或JPA。目前仅access-service显式接入，其他服务和公共库不自动引用。

## 服务接入

在需要两种工具的服务POM中添加依赖：

```xml
<!-- 映射能力按需引用，不通过Web公共库扩散。 -->
<dependency>
    <groupId>com.cloudprocure</groupId>
    <artifactId>common-mapping</artifactId>
    <version>${project.version}</version>
</dependency>
```

公共模块以普通compile依赖传递Lombok和MapStruct注解API；不能在此处把Lombok设为provided或optional，否则消费者无法仅通过此模块编译Lombok代码。父POM的Boot插件排除Lombok，避免它进入可执行JAR。MapStruct API保留以支持Mappers工厂等运行时调用。

## 版本与编译处理器

- Lombok版本沿用Boot的`lombok.version`，当前解析为1.18.46。
- MapStruct固定1.6.3，注解API与processor使用同一个版本属性。
- `lombok-mapstruct-binding`固定0.2.0，和两个处理器一起放入`maven-compiler-plugin.annotationProcessorPaths`，让MapStruct在Lombok生成属性和Builder后再分析类型。
- Boot可执行JAR显式排除Lombok；processor和binding只存在于编译处理器路径，不作为运行依赖。

Maven的JAR依赖不会继承依赖模块的build/plugins，因此注解处理器路径仍在根父POM统一配置。所有子模块继承编译配置，但只有显式依赖common-mapping的模块获得对应注解API。

IDE重新加载根Maven项目，并启用注解处理。构建使用JDK21；调整依赖后首次建议`mvn -B -ntp clean verify`，避免复用旧的重打包JAR。主源码的生成目录为各模块`target/generated-sources/annotations`，测试为`target/generated-test-sources/test-annotations`，不要手写或提交生成文件。

单模块执行spring-boot:run前，在根目录执行`mvn -B -ntp -pl common/common-webmvc,common/common-mapping -am install -DskipTests`，安装公共库与父POM。

后续增加其他注解处理器时，需要追加到父POM的annotationProcessorPaths，不能只添加普通dependency。

## 使用边界

现有record保持不变。需要可变Java Bean时按需使用`@Getter`、`@Setter`或`@Builder`。JPA实体保留无参构造及持久化约束，不直接套`@Data`生成包含凭据、懒加载关联的toString/equals/hashCode。

Mapper放在拥有转换规则的服务中，不建立通用BaseMapper或反射转换工具。引用公共配置即可获得Spring组件、构造注入和未映射目标字段报错规则；对确实不映射的字段显式声明ignore。

```java
// 映射规则属于当前服务，公共模块只统一生成约定。
@Mapper(config = CommonMapperConfig.class)
public interface ItemMapper {
    ItemResponse toResponse(Item source);
}
```

以上是用法片段，Item与ItemResponse由具体业务定义，不新增演示业务类。密码哈希等字段应通过专用响应DTO排除，不能把实体全字段映射当成对外契约。

access-service的本地`LombokMapStructTest`通过common-mapping的传递依赖编译真实getter/setter、Builder与Spring Mapper，验证属性读取、字段改名和不可变目标构造。测试源码遵循现有规则仅保留在被忽略的`src/test/`；干净克隆可能没有该测试。

## 本次验证

2026-10-24执行`mvn -B -ntp "-Dmaven.jar.forceCreation=true" verify`通过，27项本地测试失败/错误/跳过均为0。强制重新创建JAR避免依赖调整后复用旧的重打包产物；也可以使用clean verify。

检查全部八个应用JAR：仅access-service包含common-mapping与MapStruct API，其余七个不包含；全部排除Lombok、processor和binding。公共库已按上述命令安装到本机Maven仓库。未验证IDE插件或新增业务映射。任务见[Issue #11](https://github.com/modakai/CloudProcure/issues/11)。

## 官方依据

- [Lombok Maven配置](https://projectlombok.org/setup/maven)：Lombok及显式注解处理器的配置。
- [MapStruct安装](https://mapstruct.org/documentation/installation/)：注解API和processor配置。
- [MapStruct与Lombok集成](https://mapstruct.org/documentation/stable/reference/html/#_lombok)：binding处理器配置及两者协作。
