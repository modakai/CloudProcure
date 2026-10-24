-- subject 独立于登录名，避免改名导致外部身份关联失效。
CREATE TABLE user_account
(
    subject_id            VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL comment '主题id',
    login_name VARCHAR(100)                                      NOT NULL comment '登录账号',
    password_hash         VARCHAR(255)                                      NOT NULL comment '密码',
    status                VARCHAR(20)                                       NOT NULL comment '状态，例如 ACTIVE、DISABLED',
    credential_version    BIGINT                                            NOT NULL comment '账号凭据的版本',
    version               BIGINT                                            NOT NULL comment '版本号',
    PRIMARY KEY (subject_id),
    CONSTRAINT uk_user_account_login UNIQUE (login_name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin;
