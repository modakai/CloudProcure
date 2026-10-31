package com.cloudprocure.identity.config;

import com.cloudprocure.common.webmvc.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 骨架模式（profile=skeleton）的安全兜底。
 *
 * <p>骨架模式不连 MySQL/Nacos，也不启用授权服务器自动配置（见 application-skeleton.yml），
 * 因此这里只需要一条“默认拒绝”的链：证明应用能启动、健康检查可用，
 * 但不假装已经具备登录或签发 Token 的能力，也不生成示例用户。
 *
 * <p>与正式模式的关系：正式模式由 AuthorizationServerConfiguration(Order 1) +
 * IdentitySecurityConfiguration(Order 2) 提供两条链；本类只在 skeleton profile 下生效，
 * 两者不会同时存在。
 */
@Configuration(proxyBeanMethods = false)
@Profile("skeleton")
public class SkeletonSecurityConfiguration {

    /** 默认拒绝业务访问，保留CSRF防护，不生成示例登录用户。 */
    @Bean
    SecurityFilterChain skeletonSecurityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems) {
        http.authorizeHttpRequests(authorize -> authorize
                        // 健康检查给探针/编排系统使用，匿名放行。
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // 其他一律拒绝，包括发现文档：依赖就位不等于可以签发 Token。
                        .anyRequest().denyAll())
                // 未认证/无权限统一返回 Problem JSON，与正式链的响应格式保持一致。
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems.authenticationEntryPoint())
                        .accessDeniedHandler(problems.accessDeniedHandler()));
        return http.build();
    }
}
