package com.cloudprocure.identity.account;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * UserDetailsService 实现：按登录名把数据库账号转换成框架认识的 UserDetails。
 *
 * <p>契约要点：这里只负责“查”，不负责“验密码”；密码比对由 DaoAuthenticationProvider 完成。
 * 找不到账号必须抛 UsernameNotFoundException，框架会把它和“密码错误”统一成 BadCredentials，
 * 这样响应就不会泄露“账号是否存在”。
 *
 * <p>查询条件必须与账号初始化时的规范化规则一致：表排序规则是 utf8mb4_bin/ascii_bin，
 * 大小写敏感，不会自动忽略大小写或空格。
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
            // 统一的错误文案，避免通过“账号不存在/密码错误”的差异枚举账号。
            .orElseThrow(() ->
                new UsernameNotFoundException("账号或密码错误"));

        return new AccountUserDetails(
            // 作为 username：认证成功后 Authentication.getName() 返回稳定 subject。
            account.getSubjectId(),
            // BCrypt 哈希，由注入的 PasswordEncoder 校验，不在这里比对。
            account.getPasswordHash(),
            // 只有 ACTIVE 才 enabled；其余状态（如 DISABLED）都会被框架拒绝登录。
            AccountStatus.ACTIVE.equals(account.getStatus()),
            // 与密码哈希同一次查询取得的版本，随认证冻结。
            account.getCredentialVersion());
    }
}
