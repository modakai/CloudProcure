package com.cloudprocure.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.ArrayList;
import java.util.List;

/** 按Token用途设置受众，避免门户身份凭据被当作业务访问凭据。 */
@Configuration(proxyBeanMethods = false)
public class TokenClaimsConfiguration {

    @Bean
    //OAuth2TokenCustomizer: 框架准备生成 Token 时，允许应用调整部分声明。
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // shortcut: 当前只有门户用户客户端，新增服务客户端时分别设置用户与服务受众。
                // JDBC授权元数据需要恢复具体集合类型，避免List.of生成的List12被Jackson拒绝。
                context.getClaims().audience(
                        new ArrayList<>(List.of("cloudprocure-api")));
            }
        };
    }
}
