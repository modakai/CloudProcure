# 库存迁移目录

在此添加Flyway版本化SQL，例如V1__create_initial_tables.sql。当前没有业务表，不创建占位空表。

数据库由外部初始化并授予本服务独立账号；JPA使用validate，不能自动改表。identity的协议表需采用冻结Spring Security版本的官方Schema。
