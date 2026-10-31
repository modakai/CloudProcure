package com.cloudprocure.identity.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 门户客户端初始化。
 *
 * <p>{@link RegisteredClient} 是 OAuth2 的“客户端注册信息”：client_id、密钥哈希、允许的 grant、
 * 精确回调地址、scope，以及 ClientSettings/TokenSettings。框架会把它序列化进 oauth2_registered_client 表，
 * 运行时由 JdbcRegisteredClientRepository 读回；这里不做任何协议处理。
 *
 * <p>只在 identity.client.bootstrap.enabled=true 时执行：初始化属于一次性的运维动作，
 * 不能每次启动都跑（否则可能覆盖线上密钥或产生重复客户端）。
 * ApplicationRunner 在容器启动完成后执行一次，此时 Flyway 迁移和仓储都已就绪。
 */
@Component
@ConditionalOnProperty(
    prefix = "identity.client.bootstrap",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@Slf4j
public class PortalClientInitializer implements ApplicationRunner {

    // 门户的固定 client_id。第一方客户端，不开放动态注册。
    private static final String CLIENT_ID = "cloudprocure-portal";
    private final RegisteredClientRepository repository;
    private final PasswordEncoder passwordEncoder;

    // 客户端密钥配置在 Nacos 中，不随代码提交。
    private final String IDENTITY_PORTAL_CLIENT_SECRET;

    // 回调地址必须与门户实际处理 OIDC 回调的地址逐字符一致；
    // 框架只做精确匹配，不做前缀/通配，防止开放重定向。
    // TODO 后面更换对应的调用 采购门户 还没开发
    private static final String REDIRECT_URI =
        "https://portal.cloudprocure.test/login/oauth2/code/portal";

    public PortalClientInitializer(
        RegisteredClientRepository repository,
        PasswordEncoder passwordEncoder,
        @Value("${IDENTITY_PORTAL_CLIENT_SECRET}") String clientSecret) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.IDENTITY_PORTAL_CLIENT_SECRET = clientSecret;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 幂等：已存在就保持原配置。初始化不是“重置”，不能覆盖已经下发的密钥配置。
        RegisteredClient existing = repository.findByClientId(CLIENT_ID);

        if (Objects.nonNull(existing)) {
            log.info("门户客户端已存在，保持原配置不变。");
            return;
        }

        String secret = IDENTITY_PORTAL_CLIENT_SECRET;
        // secret 长度约束来自两件事：
        // - 下限 32 字符：保证熵，避免弱客户端密钥被离线爆破；
        // - 上限 72 字节：BCrypt 只使用前 72 字节，超出部分不参与校验，检查能避免“以为更安全”。
        if (secret.length() < 32
            || secret.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException(
                "客户端Secret至少32个字符，且UTF-8编码不得超过72字节");
        }

        // 注册默认客户端。下面每个配置项最终都会落到 oauth2_registered_client 的列里。
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
            // id 是数据库主键，与 client_id 不是一回事；client_id 才是协议里对外的标识。
            .clientId(CLIENT_ID)
            .clientName("CloudProcure采购门户")
            // 存的是 BCrypt 哈希（带 {bcrypt} 前缀），不是明文；兑换 Token 时框架用 PasswordEncoder 比对。
            .clientSecret(passwordEncoder.encode(secret))
            // 客户端验证方法：兑换 Token 时用 HTTP Basic 提交身份
            // （Authorization: Basic base64(client_id:secret)）。
            // 其他常见值：CLIENT_SECRET_POST（放表单）、NONE（公共客户端，无密钥）。
            .clientAuthenticationMethod(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC
            )
            // 只允许授权码模式：浏览器先拿 code，后端再用 code 换 Token，Token 不经过浏览器。
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT_URI)
            // scope=openid 是 OIDC 的开关：没有它就不会签发 ID Token。
            .scope(OidcScopes.OPENID)
            // 客户端配置。
            .clientSettings(ClientSettings.builder()
                // 强制 PKCE（S256）：即使授权码被截获，没有 code_verifier 也换不到 Token。
                // 机密客户端也建议开启，属于纵深防御。
                .requireProofKey(true)
                // 第一方门户不需要用户逐项点“同意”；第三方客户端才应设为 true。
                .requireAuthorizationConsent(false)
                .build()
            )
            // Token 配置。
            .tokenSettings(TokenSettings.builder()
                // 授权码 60 秒过期且一次性，缩短被截获后的可用窗口。
                .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                // Access Token 5 分钟；当前没有注册 refresh_token，过期后只能重新登录。
                .accessTokenTimeToLive(Duration.ofMinutes(5))
                // ID Token 用 RS256 签名，与 JWKS 公布的算法一致。
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
            )
            .build();

        // 写入 oauth2_registered_client；settings 会被序列化为 JSON 列。
        repository.save(client);
    }
}
