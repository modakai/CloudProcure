# 统一父工程与公共库BOM

状态：accepted｜日期：2026-10-31｜对应本次构建结构重构。

根POM此前同时承担父工程和聚合器，所有模块直接继承根；各服务自行抄写公共库版本（10处`${project.version}`）和可执行JAR插件（8处），`common-redis`则游离在`<modules>`之外。本次在根之下新增`common`与`services`两个`packaging=pom`的分组父工程，并把唯一的公共库版本契约收敛到`common/common-bom`：`services`父工程导入一次、`gateway`单独导入，服务POM不再写公共库版本，`spring-boot-maven-plugin`只在`services`父声明。版本仍随整仓`0.1.0-SNAPSHOT`，不为公共库单独发版。

**考虑过的方案**

- 不新增分组父工程，全部版本和插件继续放在根：层级更少，但BOM无处安放——根导入子BOM会与继承关系成环，只能让BOM不继承根，等于让BOM重复声明项目版本。
- 新建单一`platform`父工程承载common与services：多一层继承，收益与分组父相同；且为了让platform导入BOM，BOM必须移到platform之上，结构更绕。
- BOM同时托管Lombok、MapStruct、Spring Web等第三方版本：会在Boot、Cloud、SCA三个BOM之外再造一个版本来源，升级时要两处对齐。

**后果**

- 从根目录构建（含`-pl`子集）可在reactor内解析BOM；直接构建单个服务POM前必须先`install` `common-bom`，否则报`Non-resolvable import POM`。这与原先“单模块运行前先安装公共库”的前置条件性质相同，只是多一个构件。
- `common-webmvc`依赖`common-http`仍保留`${project.version}`：`common`父工程不能导入自己的子BOM，否则成环。
- `common-redis`只有占位实现，参与聚合构建但不进BOM，避免把未实现能力写进依赖契约。
