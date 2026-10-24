package com.cloudprocure.identity.account;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 首个账号只能由显式开启的本地初始化流程创建，不提供公开注册接口。 */
@Component
@ConditionalOnProperty(
    prefix = "identity.bootstrap",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@Slf4j
public class AccountInitializer implements ApplicationRunner {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    // 默认登录账号和密码已存放到Nacos中进行配置
    private final String IDENTITY_BOOTSTRAP_LOGIN;
    private final String IDENTITY_BOOTSTRAP_PASSWORD;

    public AccountInitializer(
        UserAccountRepository repository,
        PasswordEncoder passwordEncoder,
        @Value("${IDENTITY_BOOTSTRAP_LOGIN}") String IDENTITY_BOOTSTRAP_LOGIN,
        @Value("${IDENTITY_BOOTSTRAP_PASSWORD}") String IDENTITY_BOOTSTRAP_PASSWORD
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.IDENTITY_BOOTSTRAP_LOGIN = IDENTITY_BOOTSTRAP_LOGIN;
        this.IDENTITY_BOOTSTRAP_PASSWORD = IDENTITY_BOOTSTRAP_PASSWORD;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String loginName = IDENTITY_BOOTSTRAP_LOGIN;

        // 首版限定登录名字符范围，后续登录查询必须沿用相同的规范化规则。
        if (!loginName.matches("[a-z0-9][a-z0-9._@-]{2,99}")) {
            throw new IllegalArgumentException(
                "登录名需为3至100位，使用英文字母、数字或 . _ @ -");
        }

        // 初始化不是重置密码，重复执行必须保留原账号与凭据。
        if (repository.findByLoginName(loginName).isPresent()) {
            log.info("初始化账号已存在，保持原数据不变。");
            return;
        }

        String rawPassword = IDENTITY_BOOTSTRAP_PASSWORD;

        validatePasswordLength(rawPassword);

        UserAccount account = new UserAccount();
        account.setLoginName(loginName);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setStatus(AccountStatus.ACTIVE);
        account.setCredentialVersion(1L);

        // 立即执行SQL，让唯一约束等数据库错误在初始化阶段暴露。
        repository.saveAndFlush(account);

        log.info("初始化账号创建成功。");
    }

    /** BCrypt 限制的是字节数，不能只检查 Java 字符串长度。 */
    static void validatePasswordLength(String password) {
        if (password == null) {
            throw new IllegalArgumentException("密码不能为空");
        }

        int characters = password.codePointCount(0, password.length());
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;

        if (characters < 8 || bytes > 72) {
            throw new IllegalArgumentException(
                "密码至少8个字符，且UTF-8编码不得超过72字节");
        }
    }
}
