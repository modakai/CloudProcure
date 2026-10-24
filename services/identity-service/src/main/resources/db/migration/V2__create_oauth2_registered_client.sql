-- 来源：Spring Security 7.0.7 官方客户端Schema。
-- client_id唯一约束由本项目补充，防止并发初始化产生重复客户端。
CREATE TABLE oauth2_registered_client
(
    id                            VARCHAR(100)                            NOT NULL comment '主键',
    client_id                     VARCHAR(100)                            NOT NULL comment '客户端id',
    client_id_issued_at           TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL comment '客户端注册时间',
    client_secret                 VARCHAR(200)  DEFAULT NULL COMMENT '客户端密钥',
    client_secret_expires_at      TIMESTAMP     DEFAULT NULL comment '客户端密钥过期时间',
    client_name                   VARCHAR(200)                            NOT NULL comment '客户端名称',
    client_authentication_methods VARCHAR(1000)                           NOT NULL comment '客户端验证方法',
    authorization_grant_types     VARCHAR(1000)                           NOT NULL comment '客户端认证类型',
    redirect_uris                 VARCHAR(1000) DEFAULT NULL comment '重定向地址',
    post_logout_redirect_uris     VARCHAR(1000) DEFAULT NULL comment '退出登录后的重定向地址',
    scopes                        VARCHAR(1000)                           NOT NULL comment '范围',
    client_settings               VARCHAR(2000)                           NOT NULL comment '客户端设置',
    token_settings                VARCHAR(2000)                           NOT NULL comment 'token设置',
    PRIMARY KEY (id),
    CONSTRAINT uk_oauth2_registered_client_client_id UNIQUE (client_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin;
