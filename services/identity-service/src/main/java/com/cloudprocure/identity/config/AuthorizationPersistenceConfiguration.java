package com.cloudprocure.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * 授权服务器协议数据的持久化配置。
 *
 * <p>这里刻意用官方 JDBC 实现而不是 JPA：协议表（客户端、授权、同意）字段多、带 JSON 列，
 * 且必须跟随框架版本的 Schema；自己用 ORM 重写一遍，升级时最容易出现“写得进、读不出”的问题（见 ADR 0002）。
 *
 * <p>注意 {@link JdbcOperations} 是 Spring JDBC 的模板接口（JdbcTemplate 实现了它），
 * 与 JPA 的 EntityManager 是两套东西：这里不走 Hibernate 的一级缓存和事务脏检查，
 * 每个方法就是一次明确的 SQL。
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationPersistenceConfiguration {

    /**
     * 客户端注册表：/oauth2/token 收到 client_id 后，用它查出客户端配置
     * （密钥哈希、允许的 grant、精确回调地址、scope、TTL 等）。
     *
     * <p>数据来自 oauth2_registered_client 表，由 {@link com.cloudprocure.identity.client.PortalClientInitializer}
     * 初始化写入；运行时框架只读这份配置。
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(
        JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    /**
     * 授权记录服务：一次授权从“发授权码”到“换 Token”再到“UserInfo 查身份”的全过程，
     * 都在读写这条记录；授权码一次性、Token 撤销等状态也保存在这里。
     *
     * <p>为什么要自定义 JSON 映射：oauth2_authorization 的 attributes/metadata 列存 JSON，
     * 框架用 Jackson 多态反序列化（JSON 里带 @class 类型信息）还原成 Java 对象。
     * {@link SecurityJacksonModules} 提供安全相关类型的白名单模块；
     * {@link BasicPolymorphicTypeValidator} 是总闸，默认拒绝，只放行显式允许的类型。
     */
    @Bean
    OAuth2AuthorizationService authorizationService(
        JdbcOperations jdbcOperations,
        RegisteredClientRepository clients) {
        // 只额外放行 Long：认证快照里冻结的 credential_version 是 Long。
        // 提醒：JSON 整数默认会读成 Integer，这条配置是否真正命中反序列化路径，
        // 要以 AuthorizationPersistenceTest 的真实数据库往返结果为准，不能想当然。
        var typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(Long.class);
        JsonMapper mapper = JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(
                AuthorizationPersistenceConfiguration.class.getClassLoader(), typeValidator))
            .build();

        var service = new JdbcOAuth2AuthorizationService(jdbcOperations, clients);
        // 读（RowMapper）和写（ParametersMapper）必须用同一套 mapper 配置：
        // 否则写进去的类型元数据和读出来时的白名单不一致，会出现“存得进、查不出”。
        // 这个 mapper 只服务协议数据，不替换 MVC 的全局 ObjectMapper。
        service.setAuthorizationRowMapper(
            new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(clients, mapper));
        service.setAuthorizationParametersMapper(
            new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(mapper));
        return service;
    }

    /**
     * 同意记录：某用户对某客户端授权了哪些 scope。
     *
     * <p>本项目门户客户端设置了 requireAuthorizationConsent(false)，
     * 正常流程不会写这张表；保留这个 Bean 是为了将来开放第三方客户端或打开同意页时不用改装配。
     */
    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
        JdbcOperations jdbcOperations,
        RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(
            jdbcOperations, clients);
    }
}
