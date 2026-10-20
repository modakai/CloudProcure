package com.cloudprocure.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;

/** OIDC客户端尚未实现时保持默认拒绝，不能为演示放开整个网关。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** 仅开放最小健康端点；后续以真实OIDC客户端替换拒绝策略。 */
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyExchange().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
