package com.cloudprocure.identity.account;

import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号密码认证提供者。
 *
 * <p>它继承 DaoAuthenticationProvider，复用框架的模板流程：
 * retrieveUser（调 UserDetailsService 查库）→ preAuthenticationChecks（enabled/锁定等）
 * → additionalAuthenticationChecks（PasswordEncoder 比对密码）→ createSuccessAuthentication。
 * 这里只覆盖最后一步，因为只有“认证成功后要保存什么”是本项目的特殊需求。
 *
 * <p>为什么要替换 principal：认证结果会被序列化进 oauth2_authorization.attributes（用户授权后）。
 * 如果 principal 是带数据库查询对象的自定义类型，反序列化白名单就得跟着放宽；
 * 用标准 User 最稳，也不会把密码哈希带进持久化数据。
 */
public final class AccountAuthenticationProvider
        extends DaoAuthenticationProvider {

    public AccountAuthenticationProvider(UserDetailsService users) {
        // 父类保存这个 UserDetailsService，认证时用它按用户名查账号。
        super(users);
    }

    @Override
    protected Authentication createSuccessAuthentication(
        @NonNull Object principal,
        @NonNull Authentication authentication,
        @NonNull UserDetails user) {

        // 只有本项目的 AccountUserDetails 才带 credentialVersion；
        // 出现其他实现说明装配错了，直接失败，而不是悄悄签发一个没有版本的 Token。
        if (!(user instanceof AccountUserDetails account)) {
            throw new IllegalStateException("账号认证缺少凭据版本");
        }

        // 复制成标准 User：
        // - 保留 subject（username）与 authorities/enabled 等状态；
        // - password("") 是为了让持久化对象里没有可复用的密码哈希（空串并不代表能登录）。
        UserDetails safePrincipal = User.withUserDetails(account)
                .password("")
                .build();

        // 调用父类：保留框架的 authorities 映射、details 拷贝、密码升级等逻辑，不自己复制。
        AbstractAuthenticationToken result =
                (AbstractAuthenticationToken)
                        super.createSuccessAuthentication(
                                safePrincipal, authentication, user);

        // details 不参与权限判断，只是 Authentication 的附属信息，
        // 正好适合放“签发 Token 时要用到的认证快照”。
        Map<String, Object> snapshot = new LinkedHashMap<>();
        // 认证那一刻的版本。之后数据库版本变化不影响这次会话（旧会话保持旧版本）。
        snapshot.put("credential_version", account.getCredentialVersion());

        // 保留框架塞进来的 WebAuthenticationDetails（来源地址、sessionId 等），
        // 丢掉会损失排障/审计需要的认证上下文。
        if (result.getDetails() != null) {
            snapshot.put("request", result.getDetails());
        }

        result.setDetails(snapshot);

        return result;
    }
}
