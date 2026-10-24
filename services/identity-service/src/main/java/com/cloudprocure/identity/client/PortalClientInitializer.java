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
 * 固定门户客户端仅由受控初始化创建，重复运行不覆盖原配置。
 */
@Component
@ConditionalOnProperty(
    prefix = "identity.client.bootstrap",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@Slf4j
public class PortalClientInitializer implements ApplicationRunner {

    private static final String CLIENT_ID = "cloudprocure-portal";
    private final RegisteredClientRepository repository;
    private final PasswordEncoder passwordEncoder;

    // 客户端密钥 配置丢入的Nacos中
    private final String IDENTITY_PORTAL_CLIENT_SECRET;

    // 重定向认证地址 TODO 后面更换对应的调用 采购门户 还没开发
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
        // 校验默认的 客户端存不存在
        RegisteredClient existing = repository.findByClientId(CLIENT_ID);

        if (Objects.nonNull(existing)) {
            log.info("门户客户端已存在，保持原配置不变。");
            return;
        }

        String secret = IDENTITY_PORTAL_CLIENT_SECRET;
        // 门户Secret使用随机长字符串，同时遵守当前BCrypt的字节上限。
        if (secret.length() < 32
            || secret.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException(
                "客户端Secret至少32个字符，且UTF-8编码不得超过72字节");
        }

        // 注册默认客户端
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(CLIENT_ID)
            .clientName("CloudProcure采购门户")
            .clientSecret(passwordEncoder.encode(secret))
            // 客户端验证方法
            .clientAuthenticationMethod(
                // 表示门户兑换 Token 时，使用 HTTP Basic 方式提交客户端身份凭据。
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC
            )
            // 认证类型为授权码模式
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT_URI)
            // 配置范围，开放OpenId
            .scope(OidcScopes.OPENID)
            // 客户端配置
            .clientSettings(ClientSettings.builder()
                // 要求客户端使用 PKCE。
                .requireProofKey(true)
                // 固定第一方门户，不额外展示第三方授权同意页面
                .requireAuthorizationConsent(false)
                .build()
            )
            // token配置
            .tokenSettings(TokenSettings.builder()
                .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                .accessTokenTimeToLive(Duration.ofMinutes(5))
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
            )
            .build();

        // 保存默认客户端
        repository.save(client);
    }
}
