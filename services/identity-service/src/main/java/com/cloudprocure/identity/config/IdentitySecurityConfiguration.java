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
 * 业务登录链（第二条安全链）。
 *
 * <p>Spring Security 允许一个应用有多条 SecurityFilterChain，按 @Order 排序，
 * 每条链声明自己负责哪些请求。本项目里：
 * <ul>
 *   <li>@Order(1)：授权服务器协议端点（见 {@link AuthorizationServerConfiguration}）；</li>
 *   <li>@Order(2)：本类，负责表单登录、退出、健康检查、/identity/api/v1/me 和默认拒绝。</li>
 * </ul>
 *
 * <p>没有匹配到协议端点的请求最终都会落到这条链。登录流程是：
 * 浏览器访问 /oauth2/authorize → 未登录 → 协议链的入口点 302 到 /login →
 * 框架生成默认登录页 → 提交用户名密码 → 本链认证成功 → 回跳协议链继续授权。
 *
 * <p>注意：本类没有调用 http.csrf(...)，也就是沿用 Spring Security 默认开启的 CSRF 防护，
 * 所以 POST /login 和 POST /logout 都必须携带 CSRF token（测试里有缺少 token 被 403 的用例）。
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    /**
     * 登录限速器：不是安全框架组件，但被安全链上的过滤器使用。
     *
     * <p>这里把外部配置绑定成 Duration：Boot 会把 ISO-8601 的 PT5M 转成 Duration，
     * 依赖 ConversionService（测试夹具里因此专门补了 ApplicationConversionService）。
     * 各参数含义见 {@link LoginAttemptLimiter}：账号维度、来源维度、各自窗口、最大键数。
     */
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
     * 认证管理器：表单登录最终调用它来验证用户名密码。
     *
     * <p>ProviderManager 是“一组 AuthenticationProvider 的调度器”：
     * 遍历 provider，谁能处理这个 Authentication 类型就由谁认证。
     * DaoAuthenticationProvider 负责标准流程——按用户名查 UserDetails、检查账号状态、
     * 用 PasswordEncoder 比对密码、成功后构造已认证的 Authentication。
     */
    @Bean
    AuthenticationManager authenticationManager(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder) {

        AccountAuthenticationProvider provider =
            new AccountAuthenticationProvider(userDetailsService);

        // 必须与创建账号时用同一个编码器、同一套算法，
        // 否则数据库里的哈希永远验不过（编码器 id 前缀也参与判断）。
        provider.setPasswordEncoder(passwordEncoder);

        return new ProviderManager(provider);
    }

    /**
     * 登录/业务链，排在协议链之后。
     *
     * <p>@Order 数字越小越先匹配。这条链没有额外 securityMatcher，
     * 含义是“前面所有链都不匹配时，由我处理”。
     */
    @Bean
    @Order(2) // 协议安全链没有匹配的请求，再由现有登录安全链处理。
    SecurityFilterChain SecurityFilterChain(
        HttpSecurity http,
        AuthenticationManager authenticationManager,
        ProblemSecurityHandlers problems,
        LoginAttemptLimiter loginAttemptLimiter,
        ObjectMapper mapper
    ) {
        // SavedRequest 机制：Spring Security 会把“被拦截前的原始请求”
        // （这里是 /oauth2/authorize?...）存进 HttpSession 的固定属性，登录成功处理器再取出来回跳。
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // 让本链“不保存”新的 SavedRequest：否则浏览器打开登录页后发出的任何后台请求，
        // 都可能把协议链保存的授权请求覆盖掉，登录成功就跳到错误的位置。
        // 注意只是不保存，不阻止读取：两条链共用同一个 session，
        // 登录成功处理器仍能读到协议链存的那份 SavedRequest。
        requestCache.setRequestMatcher(request -> false);

        http
            // 把限速过滤器插到“密码认证过滤器”之前、CSRF 过滤器之后：
            // - 在 CSRF 之后：先由框架校验 CSRF，缺 token 的请求根本到不了这里；
            // - 在密码认证之前：在 BCrypt 这种故意很慢的校验之前限流，避免暴力破解把 CPU 打满。
            // 刻意不把 LoginAttemptFilter 声明成 @Bean：Boot 会把容器里的 Filter 自动注册到 Servlet 容器，
            // 那样它会脱离这条安全链再执行一次，顺序和次数都不可控。
            .addFilterBefore(new LoginAttemptFilter(loginAttemptLimiter, mapper),
                    UsernamePasswordAuthenticationFilter.class)
            // 换掉默认的 SavedRequest 缓存实现（原因见上面 requestCache 的注释）。
            .requestCache(cache -> cache.requestCache(requestCache))
            // formLogin 使用的认证管理器；不设置的话会依赖全局 AuthenticationManager。
            .authenticationManager(authenticationManager)
            // 授权规则：从上往下匹配，第一条命中就生效，所以具体规则必须写在 anyRequest 之前。
            .authorizeHttpRequests(authorize -> authorize
                // 健康检查给容器/网关探针使用，必须匿名可访问。
                .requestMatchers("/actuator/health","/actuator/health/**")
                .permitAll()
                // 目前唯一对外的业务接口：返回当前登录者的 subject。
                .requestMatchers(HttpMethod.GET, "/identity/api/v1/me")
                .authenticated()
                // 其余全部拒绝（fail-closed）：没想清楚要不要开放的接口默认不放行。
                .anyRequest()
                .denyAll()
            )
            // 表单登录：不指定 loginPage，使用框架生成的默认登录页（GET /login）。
            // defaultSuccessUrl 的语义：session 里有 SavedRequest 就优先回跳（授权流程），
            // 否则才跳 /identity/api/v1/me。
            // permitAll 让未登录用户也能打开 /login，否则会 302 到 /login 再 302，形成重定向循环。
            .formLogin(form -> form
                .defaultSuccessUrl("/identity/api/v1/me")
                .permitAll()
            )
            // 退出：默认就是 POST /logout（受 CSRF 保护），这里只改成功后去哪。
            // 注意这是“本服务会话”的退出；OIDC 的 RP-Initiated Logout 是另一套端点，当前未配置。
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            // 异常处理：
            // - 未登录 → 401 Problem JSON（UNAUTHENTICATED）
            // - 已登录但无权限 → 403 Problem JSON
            // 这里用 defaultAuthenticationEntryPointFor + AnyRequestMatcher，而不是 exceptionHandling().authenticationEntryPoint(...)：
            // 项目实测全局入口点会让框架认为登录入口已由应用提供，从而不再生成默认登录页；
            // 按请求匹配注册可以同时保留“JSON 401”和“默认登录页”。
            .exceptionHandling(exception -> exception
                .defaultAuthenticationEntryPointFor(problems.authenticationEntryPoint(), AnyRequestMatcher.INSTANCE)
                .accessDeniedHandler(problems.accessDeniedHandler())
            );
        return http.build();
    }

}
