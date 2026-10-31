package com.cloudprocure.identity.account;

import java.io.Serial;
import java.util.Collections;

import lombok.Getter;
import org.springframework.security.core.userdetails.User;

/**
 * 认证阶段使用的 UserDetails。
 *
 * <p>它继承框架的 User（UserDetails 的默认实现），额外带一个 credentialVersion：
 * 版本与密码哈希在“同一次数据库查询”里取出，保证两者是同一时刻的快照，
 * 不会出现“用旧密码验过、却记下新版本”的错配。
 *
 * <p>username 用 subjectId 而不是登录名：subject 是稳定身份标识，
 * 改名或更换登录方式不会让外部系统关联失效；登录名只负责“找到账号”。
 */
@Getter
public final class AccountUserDetails extends User {

    // User 实现了 Serializable，子类显式声明版本号，避免序列化兼容性检查告警。
    @Serial
    private static final long serialVersionUID = 1L;

    // 认证时冻结的凭据版本，供 AccountAuthenticationProvider 写入认证快照。
    private final long credentialVersion;

    public AccountUserDetails(
            String subject,
            String passwordHash,
            boolean enabled,
            Long credentialVersion) {

        // User 构造器的四个 boolean 依次是：enabled、accountNonExpired、credentialsNonExpired、accountNonLocked。
        // 本项目只用 enabled 表达账号停用，其余维度恒为 true；
        // 将来要支持锁定/过期，应落到 UserAccount.status 上再映射，而不是在别处临时判断。
        super(
                subject,
                passwordHash,
                enabled,
                true,
                true,
                true,
                Collections.emptyList());

        // 0 或负数没有业务含义，而且会让 TokenClaimsConfiguration 拒绝签发；提前在这里失败更清楚。
        if (credentialVersion == null || credentialVersion < 1) {
            throw new IllegalArgumentException("账号凭据版本必须为正整数");
        }

        this.credentialVersion = credentialVersion;
    }
}
