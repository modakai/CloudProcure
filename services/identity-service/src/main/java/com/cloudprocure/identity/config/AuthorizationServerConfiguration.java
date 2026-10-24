package com.cloudprocure.identity.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/** 授权、Token、发现文档、JWKS、UserInfo 等协议端点 标准协议处理交给框架，复用已配置的JDBC仓储和持久化签名密钥。 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http,
        RegisteredClientRepository clients,
        OAuth2AuthorizationService authorizations,
        OAuth2AuthorizationConsentService consents,
        AuthorizationServerSettings settings
    ) {
        http.oauth2AuthorizationServer(server -> {
            // 只接管框架协议端点，避免拦截登录页和其他请求。
            http.securityMatcher(server.getEndpointsMatcher());
            server.
                registeredClientRepository(clients)
                .authorizationService(authorizations)
                .authorizationConsentService(consents)
                .authorizationServerSettings(settings)
                .oidc(Customizer.withDefaults());
        })
            .authorizeHttpRequests(authorize  -> authorize.anyRequest().authenticated())
            .exceptionHandling(exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML))
            );
        return http.build();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
        @Value("${OIDC_ISSUER_URI}") String issuer) {

        // 明确身份签发方，不能随请求Host变化。
        return AuthorizationServerSettings.builder()
            .issuer(issuer)
            .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> keys) {
        // UserInfo等OIDC能力需要JWT解码器，复用同一受控密钥来源。
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
    }
}
