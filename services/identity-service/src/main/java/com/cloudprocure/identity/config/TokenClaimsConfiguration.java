package com.cloudprocure.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 按Token用途设置受众，避免门户身份凭据被当作业务访问凭据。 */
@Configuration(proxyBeanMethods = false)
public class TokenClaimsConfiguration {

    @Bean
    //OAuth2TokenCustomizer: 框架准备生成 Token 时，允许应用调整部分声明。
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN
                .equals(context.getTokenType());

            boolean idToken = OidcParameterNames.ID_TOKEN
                .equals(context.getTokenType().getValue());

            if (!accessToken && !idToken) {
                return;
            }

            Object details = context.getPrincipal().getDetails();

            // 旧会话或旧授权缺少快照时拒绝签发，不能默认补成当前版本。
            if (!(details instanceof Map<?, ?> snapshot)
                || !(snapshot.get("credential_version")
                instanceof Number version)
                || version.longValue() < 1) {
                throw new OAuth2AuthenticationException(
                    OAuth2ErrorCodes.INVALID_GRANT);
            }

            context.getClaims().claim(
                "credential_version", version.longValue());

            if (accessToken) {
                // shortcut: 当前只有门户用户客户端，新增服务身份时分别设置受众。
                // ArrayList兼容当前JDBC授权元数据的安全反序列化。
                context.getClaims().audience(
                    new ArrayList<>(List.of("cloudprocure-api")));
            }
        };
    }
}
