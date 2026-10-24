package com.cloudprocure.trade.config;

import com.cloudprocure.common.webmvc.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 本地集成模式校验真实JWT；租户和资源授权将在业务用例中继续实现。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class ResourceServerSecurityConfiguration {

    /** 仅接收Bearer Token的无状态API不使用浏览器Cookie认证。 */
    @Bean
    SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems) {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems.bearerAuthenticationEntryPoint())
                        .accessDeniedHandler(problems.bearerAccessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems.bearerAuthenticationEntryPoint())
                        .accessDeniedHandler(problems.bearerAccessDeniedHandler()));
        return http.build();
    }
}
