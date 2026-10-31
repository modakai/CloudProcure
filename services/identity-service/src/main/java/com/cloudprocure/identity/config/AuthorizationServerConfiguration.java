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

/**
 * 授权服务器装配：把 Spring Security 7 的授权服务器（OAuth2/OIDC Provider）装进一条独立过滤器链。
 *
 * <p>一句话理解：本类不实现任何协议细节，只把“框架要用的东西”交给框架——
 * <ul>
 *   <li>客户端从哪查：{@link RegisteredClientRepository}</li>
 *   <li>授权码/Token 存哪：{@link OAuth2AuthorizationService}</li>
 *   <li>用户同意记录存哪：{@link OAuth2AuthorizationConsentService}</li>
 *   <li>issuer 是谁：{@link AuthorizationServerSettings}</li>
 *   <li>用什么私钥签名：{@link JWKSource}（见 {@link SigningKeyConfiguration}）</li>
 * </ul>
 * 授权码的生成与校验、PKCE、Token 签发、发现文档、JWKS、UserInfo 都由框架的端点过滤器完成。
 *
 * <p>为什么不用 Boot 自动配置：application.yml 里排除了
 * OAuth2AuthorizationServerAutoConfiguration，因为本项目要显式控制链路顺序、JDBC 仓储和异常响应格式；
 * 自动配置会再建一套与之竞争的默认链，行为和顺序都不受控。
 *
 * <p>为什么必须是 {@code @Order(1)}：FilterChainProxy 按 @Order 从小到大依次询问每条链是否匹配，
 * 第一条匹配的链处理请求，后面的链不再执行。协议端点如果排在业务链之后，
 * 会先被业务链的 denyAll 拒绝，登录根本走不到授权。
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfiguration {

    /**
     * 协议链：只匹配授权服务器端点。
     *
     * <p>SecurityFilterChain 是“一整条安全过滤链”的装配结果；方法参数由 Spring 注入，
     * 正好是上面类注释里那五样依赖（本项目全部是 JDBC 实现）。
     *
     * <p>顺序说明：@Order(1) 的方法先于业务链的 @Order(2) 执行匹配，
     * 但真正决定顺序的是 @Order，方法声明位置本身不参与排序。
     */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http,
        RegisteredClientRepository clients,
        OAuth2AuthorizationService authorizations,
        OAuth2AuthorizationConsentService consents,
        AuthorizationServerSettings settings
    ) {
        // oauth2AuthorizationServer(...) 是授权服务器的配置器入口（Configurer）：
        // 它会把 /oauth2/authorize、/oauth2/token、/oauth2/jwks、/userinfo、
        // /.well-known/openid-configuration 等端点的过滤器注册进这条链。
        // lambda 里做的事是：覆盖这些端点的默认依赖（默认是内存实现，重启即丢）。
        http.oauth2AuthorizationServer(server -> {
            // securityMatcher 决定“这条链匹配哪些请求”。
            // getEndpointsMatcher() 是框架给出的端点集合，比手写 URL 列表可靠：
            // 框架调整端点时这里不用同步修改，也不会误伤 /login 和业务接口。
            http.securityMatcher(server.getEndpointsMatcher());

            server.
                registeredClientRepository(clients)      // 谁能作为客户端来换 Token（oauth2_registered_client）
                .authorizationService(authorizations)    // 授权码、Token、授权状态（oauth2_authorization）
                .authorizationConsentService(consents)   // 用户同意过哪些 scope（oauth2_authorization_consent）
                .authorizationServerSettings(settings)   // issuer 等全局设置
                // 打开 OIDC 层：发现文档里的 OIDC 元数据、ID Token、UserInfo、OIDC 退出等。
                // 不调用这一行就只是纯 OAuth2 授权服务器，没有 OIDC 能力。
                .oidc(Customizer.withDefaults());
        })
            // 兜底授权规则：凡是能走到“授权过滤器”的请求必须已经认证。
            // 注意：token/jwks/discovery 等端点由各自的端点过滤器在授权过滤器之前处理并直接返回响应，
            // 所以不会被这条规则挡住；它真正挡的是未登录访问 /oauth2/authorize 这类需要用户身份的请求。
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            // 认证入口点：未登录访问协议端点时怎么响应。
            // 这里只对 Accept 含 text/html 的浏览器请求 302 到 /login；
            // 其他请求（JSON 客户端）不匹配这个入口点，继续落到业务链，由业务链返回 Problem JSON 401。
            // 之所以用“按请求类型匹配”的入口点而不是全局 entryPoint：
            // 一是不能覆盖业务链的 JSON 401 处理，二是项目实测全局入口点会让框架不再生成默认登录页。
            .exceptionHandling(exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML))
            );
        return http.build();
    }

    /**
     * issuer：OIDC 的“身份签发方”标识。
     *
     * <p>它会被写进发现文档的 issuer 字段和 ID Token 的 iss 声明，门户和服务都会用这两处做校验，
     * 所以必须同时满足两点：
     * 1) 与浏览器实际访问 identity 的地址一致（协议测试里固定是 http://127.0.0.1:9000）；
     * 2) 稳定不变，不能从请求 Host 或转发头动态推导，否则换域名、加代理、DNS 变更都会让校验失败。
     *
     * <p>这里刻意不给默认值：issuer 猜错比启动直接失败更危险，错误会拖到客户端校验 Token 时才暴露。
     */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(
        @Value("${OIDC_ISSUER_URI}") String issuer) {

        return AuthorizationServerSettings.builder()
            .issuer(issuer)
            .build();
    }

    /**
     * JWT 解码器：给框架内部需要“再读一次 JWT”的能力使用（例如 UserInfo）。
     *
     * <p>解码器只信任本服务 {@link JWKSource} 里的公钥，不会信任调用方带进来的任意密钥；
     * 与签发共用同一把 key，避免出现“签得出、验不过”的配置不一致。
     */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> keys) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
    }
}
