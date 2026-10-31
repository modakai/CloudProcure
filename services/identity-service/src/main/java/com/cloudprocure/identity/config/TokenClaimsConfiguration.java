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

/**
 * JWT 声明定制：在框架把 Token 内容组装好、还没签名之前插入自己的声明。
 *
 * <p>{@link OAuth2TokenCustomizer} 是框架提供的扩展点，token endpoint 每次签发 JWT 时都会调用。
 * 这里做两件事：
 * 1) 强制写入认证时冻结的 credential_version，缺快照就拒绝签发；
 * 2) 给 Access Token 设置业务资源服务器受众，避免门户身份凭据被当成业务 API 凭据。
 *
 * <p>ID Token 的 aud 必须是 client_id（框架自动设置），这里绝不能覆盖，否则门户校验 ID Token 会失败。
 */
@Configuration(proxyBeanMethods = false)
public class TokenClaimsConfiguration {

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            // JwtEncodingContext 里能拿到：tokenType（这次签什么）、principal（谁认证的）、
            // registeredClient（给哪个客户端）、claims（可修改的声明构造器）。
            // Access Token 用框架的常量枚举判断。
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN
                .equals(context.getTokenType());

            // OIDC 的 ID Token 在框架里不是枚举，而是字符串 "id_token"（OIDC 规范参数名），
            // 所以要比较 getValue()。
            boolean idToken = OidcParameterNames.ID_TOKEN
                .equals(context.getTokenType().getValue());

            // 授权码、refresh token 等不是这里的处理目标，直接放过。
            if (!accessToken && !idToken) {
                return;
            }

            // principal.getDetails() 就是 AccountAuthenticationProvider 写入的快照。
            Object details = context.getPrincipal().getDetails();

            // 兼容旧会话/旧授权：授权记录里没有版本快照时，不能“默认补当前版本”，
            // 否则等于把未知凭据当成有效凭据。这里选择拒绝签发并返回 invalid_grant。
            // 用 Number 而不是 Long：JDBC JSON 反序列化后整数可能是 Integer。
            if (!(details instanceof Map<?, ?> snapshot)
                || !(snapshot.get("credential_version")
                instanceof Number version)
                || version.longValue() < 1) {
                throw new OAuth2AuthenticationException(
                    OAuth2ErrorCodes.INVALID_GRANT);
            }

            // 两种 Token 都带版本：下游刷新/高危操作据此判断“这个授权是不是旧凭据签的”。
            context.getClaims().claim(
                "credential_version", version.longValue());

            if (accessToken) {
                // Access Token 是给资源服务器（业务服务）用的，aud 必须是资源服务器认识的标识。
                // 当前只有一个受众 cloudprocure-api；将来新增服务身份（client_credentials）时，
                // 必须按客户端分别设置受众，不能继续用这个固定值，否则用户 Token 和服务 Token 的边界会混。
                // 用 ArrayList 是为了兼容 JDBC 授权元数据的安全反序列化（需要具体集合类型）。
                context.getClaims().audience(
                    new ArrayList<>(List.of("cloudprocure-api")));
            }
        };
    }
}
