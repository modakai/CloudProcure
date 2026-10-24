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

/** 保留框架密码认证流程，只在成功后建立可持久化的身份快照。 */
public final class AccountAuthenticationProvider
        extends DaoAuthenticationProvider {

    public AccountAuthenticationProvider(UserDetailsService users) {
        super(users);
    }

    @Override
    protected Authentication createSuccessAuthentication(
        @NonNull Object principal,
        @NonNull Authentication authentication,
        @NonNull UserDetails user) {

        if (!(user instanceof AccountUserDetails account)) {
            throw new IllegalStateException("账号认证缺少凭据版本");
        }

        // 持久化主体使用标准User，并移除密码哈希，避免保存自定义查询对象。
        UserDetails safePrincipal = User.withUserDetails(account)
                .password("")
                .build();

        // 保留父类的成功认证处理，不自行复制密码检查或认证强度逻辑。
        AbstractAuthenticationToken result =
                (AbstractAuthenticationToken)
                        super.createSuccessAuthentication(
                                safePrincipal, authentication, user);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("credential_version", account.getCredentialVersion());

        // 保留原有请求详情，避免丢失框架提供的来源等认证上下文。
        if (result.getDetails() != null) {
            snapshot.put("request", result.getDetails());
        }

        result.setDetails(snapshot);

        return result;
    }
}
