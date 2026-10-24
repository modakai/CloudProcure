package com.cloudprocure.identity.account;

import java.io.Serial;
import java.util.Collections;

import lombok.Getter;
import org.springframework.security.core.userdetails.User;

/** 供密码认证阶段使用，将密码编码值与同一次查询取得的凭据版本绑定。 */
@Getter
public final class AccountUserDetails extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long credentialVersion;

    public AccountUserDetails(
            String subject,
            String passwordHash,
            boolean enabled,
            Long credentialVersion) {

        super(
                subject,
                passwordHash,
                enabled,
                true,
                true,
                true,
                Collections.emptyList());

        if (credentialVersion == null || credentialVersion < 1) {
            throw new IllegalArgumentException("账号凭据版本必须为正整数");
        }

        this.credentialVersion = credentialVersion;
    }
}
