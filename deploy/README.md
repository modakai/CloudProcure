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

Nacos控制台使用本机18080端口，SDK访问8848和9848。按当前版本的控制台初始化管理员，创建独立开发命名空间、客户端账号与权限，不使用公开默认密码。创建每个应用的Data ID：`gateway.yaml`、`identity-service.yaml`及六个业务服务的`<服务名>.yaml`，Group使用DEFAULT_GROUP。可先填入以下非敏感标记，启动必需的数据库及认证参数继续通过应用环境变量传入：

```yaml
# 仅作为存在性明确的本地配置，不能替代真实安全参数。
cloudprocure:
  environment: local
```

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

数据保存在命名卷中；普通`down`保留卷。修改环境变量不会自动轮换已初始化MySQL账号密码，初始化SQL也不会在已有卷上重新执行。不要通过删除数据卷修复密码或迁移问题。
