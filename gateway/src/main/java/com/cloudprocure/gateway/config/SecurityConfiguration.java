package com.cloudprocure.gateway.config;

import com.cloudprocure.common.http.CommonErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** OIDC客户端尚未实现时保持默认拒绝，不能为演示放开整个网关。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** 仅开放最小健康端点；后续以真实OIDC客户端替换拒绝策略。 */
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemWebHandler problems) {
        return http
                .csrf(csrf -> csrf.accessDeniedHandler((exchange, exception) ->
                        problems.write(exchange, CommonErrorCode.FORBIDDEN)))
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyExchange().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, exception) ->
                                problems.write(exchange, CommonErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((exchange, exception) ->
                                problems.write(exchange, CommonErrorCode.FORBIDDEN)))
                .build();
    }
}
