package com.cloudprocure.identity.account;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

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

        return new AccountUserDetails(
            account.getSubjectId(),
            account.getPasswordHash(),
            AccountStatus.ACTIVE.equals(account.getStatus()),
            account.getCredentialVersion());
    }
}
