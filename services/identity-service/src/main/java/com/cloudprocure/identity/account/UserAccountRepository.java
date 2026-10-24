package com.cloudprocure.identity.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 账号仓储接口
 */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {


    /**
     * 根据 loginName 获取用户账号
     * @param loginName 登录账号
     * @return 用户的账号
     */
    Optional<UserAccount> findByLoginName(String loginName);
}
