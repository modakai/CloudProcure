# 本地中间件

本目录提供MySQL、Redis与Nacos开发配置。镜像tag与manifest digest已固定；尚未运行容器联调、漏洞扫描或恢复演练。RocketMQ、监控和服务容器部署留到相应用例接入时添加。

## 启动

启动Docker Desktop的Linux引擎，将`.env.example`复制为`deploy/.env`并填写全部变量，再从项目根目录执行：

```powershell
# 验证配置时使用--quiet，避免把替换后的密码输出到终端。
docker compose --env-file deploy/.env -f deploy/compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yml up -d
docker compose --env-file deploy/.env -f deploy/compose.yml ps
```

密码使用高熵随机值；Nacos Token Secret为至少32随机字节的Base64编码，身份键与身份值独立设置。`.env`只供Compose替换，不会自动注入IDE或宿主机Java进程。应用所需变量按[骨架指南](../docs/14-骨架搭建与启动指南.md)配置。

Nacos控制台使用本机18080端口，SDK访问8848和9848。按当前版本的控制台初始化管理员，创建独立开发命名空间、客户端账号与权限，不使用公开默认密码。

### 发布Nacos配置

`deploy/nacos/`提供八个应用的最小配置，文件名与`nacos:${spring.application.name}.yaml`要求的Data ID一致：`gateway.yaml`、`identity-service.yaml`、`access-service.yaml`、`catalog-service.yaml`、`trade-service.yaml`、`inventory-service.yaml`、`payment-service.yaml`、`fulfillment-service.yaml`。

1. 打开本机控制台`http://127.0.0.1:18080`，进入配置管理，选择与应用`NACOS_NAMESPACE`一致的命名空间（使用命名空间ID）。
2. 逐个新建配置，Data ID填写完整文件名（保留`.yaml`后缀），Group填写`DEFAULT_GROUP`，格式选择`YAML`。
3. 将对应文件内容粘贴到配置内容中并发布，然后启动对应应用。目录中的文件不会因为Compose启动而自动发布到Nacos。

七个持久化服务的配置包含各自的`<服务名大写>_DB_URL`、`_DB_USERNAME`和`_DB_PASSWORD`，供本机应用配置中的占位符引用；Gateway不配置数据库。地址`localhost:3306`适用于Java进程运行在Windows宿主机的情况，应用容器化后需改用实际数据库地址。需先创建对应数据库账号并授权，发布配置不会自动创建账号或修改数据库密码；payment、fulfillment同样需要准备账号。

这些文件按本地开发约定填写了明文密码，提交到GitHub会暴露这些值，不能复用于其他环境。Nacos连接参数与OIDC参数仍由本机`application-local.yml`或Java进程的环境变量提供；本机同名配置或环境变量可能影响最终解析结果，请保持一致。配置发布不代表数据库或认证已就绪，不要覆盖已经存在的有效配置。

## 数据库账号

初始化SQL只建立七个逻辑库，不建立通用应用超级账号。首次启动后进入MySQL，分别为各服务创建独立账号并只授权其对应库。例如access服务：

```powershell
# 交互输入root密码，避免将其写入命令历史。
docker compose --env-file deploy/.env -f deploy/compose.yml exec mysql mysql -uroot -p
```

```sql
-- 将占位文本替换为独立随机密码；不要实际使用占位密码。
CREATE USER 'access_app'@'%' IDENTIFIED BY '替换为独立随机密码';
GRANT ALL PRIVILEGES ON access_db.* TO 'access_app'@'%';
```

对identity、catalog、trade、inventory、payment、fulfillment同样操作。本地为了Flyway迁移在自有库授予DDL权限；生产需拆分迁移账号与仅DML的运行账号。JPA实体与迁移SQL尚未实现，不以数据库创建成功宣称业务持久化已通过。

数据通过绑定挂载保存在Windows本地目录`D:/ProgramData/docker/cloud/`下，分别使用`mysql/data`、`redis/data`、`nacos/data`和`nacos/logs`；请确保Docker Desktop可以访问这些目录，缺失的目录会在启动时自动创建。`down`不会删除这些宿主机目录。

从命名卷切换到目录挂载不会自动迁移原有数据，空目录会触发首次初始化。如需保留旧数据，先停止相关容器并备份、迁移到对应目录，确认数据完整后再启动；迁移完成前保留原命名卷。修改环境变量不会自动轮换已初始化MySQL账号密码，初始化SQL也不会在已有数据目录上重新执行。不要通过删除数据目录修复密码或迁移问题。
