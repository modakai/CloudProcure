package com.cloudprocure.identity.config;

import com.cloudprocure.common.webmvc.ProblemSecurityHandlers;
import com.cloudprocure.identity.account.AccountAuthenticationProvider;
import com.cloudprocure.identity.account.LoginAttemptFilter;
import com.cloudprocure.identity.account.LoginAttemptLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * 认证配置
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    @Bean
    LoginAttemptLimiter loginAttemptLimiter(
            @Value("${identity.login-rate-limit.account-attempts:5}") int accountAttempts,
            @Value("${identity.login-rate-limit.account-window:PT5M}") Duration accountWindow,
            @Value("${identity.login-rate-limit.source-attempts:30}") int sourceAttempts,
            @Value("${identity.login-rate-limit.source-window:PT1M}") Duration sourceWindow,
            @Value("${identity.login-rate-limit.max-entries:10000}") int maxEntries) {
        return new LoginAttemptLimiter(accountAttempts, accountWindow, sourceAttempts, sourceWindow, maxEntries);
    }

    /**
     * 认证管理器
     */
    @Bean
    AuthenticationManager authenticationManager(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder) {

        AccountAuthenticationProvider provider =
            new AccountAuthenticationProvider(userDetailsService);

        // 使用与初始化账号时一致的编码器验证数据库密码。
        provider.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(provider);
    }

    @Bean
    @Order(2) // 协议安全链没有匹配的请求，再由现有登录安全链处理。
    SecurityFilterChain SecurityFilterChain(
        HttpSecurity http,
        AuthenticationManager authenticationManager,
        ProblemSecurityHandlers problems,
        LoginAttemptLimiter loginAttemptLimiter,
        ObjectMapper mapper
    ) {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // 普通接口不覆盖协议链保存的授权请求，但登录成功仍可读取同一会话中的回跳地址。
        requestCache.setRequestMatcher(request -> false);

        http
            // 放在 CSRF 之后、密码认证之前，且不声明 Filter Bean，避免 Boot 重复注册。
            .addFilterBefore(new LoginAttemptFilter(loginAttemptLimiter, mapper),
                    UsernamePasswordAuthenticationFilter.class)
            .requestCache(cache -> cache.requestCache(requestCache))
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
