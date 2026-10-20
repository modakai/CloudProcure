package com.cloudprocure.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/** 认证服务尚未实现协议配置，依赖就位不等于可签发Token。 */
@Configuration(proxyBeanMethods = false)
public class SkeletonSecurityConfiguration {

    /** 默认拒绝业务访问，保留CSRF防护，不生成示例登录用户。 */
    @Bean
    SecurityFilterChain skeletonSecurityFilterChain(HttpSecurity http) {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
