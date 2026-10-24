package com.cloudprocure.identity.config;

import com.cloudprocure.common.webmvc.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

/**
 * 认证配置
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    /**
     * 认证处理器
     */
    @Bean
    AuthenticationManager authenticationManager(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider =
            new DaoAuthenticationProvider(userDetailsService);

        // 使用与初始化账号时一致的编码器验证数据库密码。
        provider.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain SecurityFilterChain(
        HttpSecurity http,
        AuthenticationManager authenticationManager,
        ProblemSecurityHandlers problems
    ) {
        http
            .authenticationManager(authenticationManager)
            // 请求认证拦截配置
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health","/actuator/health/**")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/identity/api/v1/me")
                .authenticated()
                .anyRequest()
                .denyAll()
            )
            // 登录配置
            .formLogin(form -> form
                .defaultSuccessUrl("/identity/api/v1/me")
                .permitAll()
            )
            // 退出登录配置
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            // 异常处理配置
            .exceptionHandling(exception -> exception
                // 按请求匹配保留JSON 401；全局入口会使框架跳过默认登录页生成。
                .defaultAuthenticationEntryPointFor(problems.authenticationEntryPoint(), AnyRequestMatcher.INSTANCE)
                .accessDeniedHandler(problems.accessDeniedHandler())
            );
        return http.build();
    }

}
