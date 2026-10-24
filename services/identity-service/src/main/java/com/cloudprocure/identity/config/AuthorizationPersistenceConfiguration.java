package com.cloudprocure.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** 协议客户端使用框架JDBC仓储，避免重写官方持久化模型。 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationPersistenceConfiguration {

    /**
     * 客户端注册 仓储服务
     *  用于操作oauth2 的客户端
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(
        JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    /**
     * 授权记录
     *  用户登录后，需要授权的应用
     */
    @Bean
    OAuth2AuthorizationService authorizationService(
        JdbcOperations jdbcOperations,
        RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(
            jdbcOperations, clients);
    }

    /**
     * 授权同意记录
     */
    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
        JdbcOperations jdbcOperations,
        RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(
            jdbcOperations, clients);
    }
}
