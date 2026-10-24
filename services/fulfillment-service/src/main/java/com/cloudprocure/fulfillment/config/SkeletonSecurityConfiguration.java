package com.cloudprocure.fulfillment.config;

import com.cloudprocure.common.webmvc.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 未接入真实认证配置时，仅允许最小健康检查。 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
public class SkeletonSecurityConfiguration {

    /** 默认拒绝业务访问，保留CSRF防护，不生成示例登录用户。 */
    @Bean
    SecurityFilterChain skeletonSecurityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems) {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems.authenticationEntryPoint())
                        .accessDeniedHandler(problems.accessDeniedHandler()));
        return http.build();
    }
}
