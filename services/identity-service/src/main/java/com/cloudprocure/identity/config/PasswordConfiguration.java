package com.cloudprocure.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * 密码加密配置
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {

    @Bean
    PasswordEncoder passwordEncoder(@Value("${IDENTITY_BCRYPT_COST}") int cost) {
        // 保存算法标识，使后续验证能识别数据库中密码的编码方式。
        return new DelegatingPasswordEncoder(
            "bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(cost)));
    }
}
