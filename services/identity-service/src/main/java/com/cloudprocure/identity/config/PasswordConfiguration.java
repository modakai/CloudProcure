package com.cloudprocure.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * 密码编码配置。
 *
 * <p>用 {@link DelegatingPasswordEncoder} 而不是直接 new BCryptPasswordEncoder：
 * 它把算法标识写进哈希（形如 {bcrypt}$2a$...），验证时按前缀选择算法。
 * 这样以后更换算法或调整 cost 时，旧密码仍然可以验证，不需要强制所有人重置。
 *
 * <p>cost 从环境变量读取：BCrypt 的 cost 每 +1，计算量翻倍，
 * 必须按目标硬件和登录并发实测后定型，不能拍脑袋写死。
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {

    @Bean
    PasswordEncoder passwordEncoder(@Value("${IDENTITY_BCRYPT_COST}") int cost) {
        // Map 的 key 必须与存入时的算法 id 一致；这里只注册 bcrypt。
        // 注意 BCrypt 只使用密码的前 72 字节，初始化账号和客户端密钥都做了字节长度校验。
        return new DelegatingPasswordEncoder(
            "bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(cost)));
    }
}
