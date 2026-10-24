package com.cloudprocure.identity.account;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 *
 */
@Service
@Slf4j
public class AccountUserDetailsService implements UserDetailsService {

    private final UserAccountRepository repository;

    public AccountUserDetailsService(UserAccountRepository repository) {
        this.repository = repository;
    }


    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {

        UserAccount account = repository.findByLoginName(username)
            .orElseThrow(() ->
                new UsernameNotFoundException("账号或密码错误"));

        return User.withUsername(account.getSubjectId())
            .password(account.getPasswordHash())
            // 登录身份不自动拥有企业成员角色或管理员权限。
            .authorities(Collections.emptyList())
            .disabled(!AccountStatus.ACTIVE.equals(account.getStatus()))
            .build();
    }
}
