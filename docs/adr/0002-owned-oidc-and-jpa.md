# 自建OIDC认证服务，业务数据采用Spring Data JPA

状态：accepted（设计采纳，尚未实现）｜日期：2026-10-20｜对应ADR-004/005，替代v1.0的外部IdP与业务JdbcClient默认方案。

用户将标准身份认证和JPA明确纳入学习目标，因此新增identity-service，基于Spring Security 7授权服务器能力提供OIDC，Gateway保留OAuth2 Client，业务服务使用Resource Server。当前只有一个采购门户，不为证明SSO虚构第二系统，也不同时维护Keycloak、Sa-Token或手写协议主线。identity拥有账号与凭据，access保留企业成员和业务权限，二者以稳定subject关联。

业务数据采用Boot管理的Spring Data JPA/Hibernate；关键库存与订单状态仍显式控制版本、条件和影响行数。认证协议仓储复用官方JDBC实现，不为统一ORM重写框架数据模型。代价是增加账号、授权、密钥、刷新退出及ORM并发/查询测试，预计增加36～52小时；具体约束见[认证设计](../13-自建OIDC认证服务设计.md)与[数据设计](../05-领域模型与数据设计.md)。
