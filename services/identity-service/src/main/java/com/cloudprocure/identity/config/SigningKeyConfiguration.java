package com.cloudprocure.identity.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * 只加载受控持久化密钥，文件缺失或损坏时不生成替代密钥。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class SigningKeyConfiguration {

    private static final String KEY_ALIAS = "identity-signing-v1";

    /**
     *   PKCS12文件                   <br/>
     *     ↓ KeyStore              <br/>
     *   Java密钥容器               <br/>
     *     ↓ RSAKey                    <br/>
     *   JWK格式的RSA密钥             <br/>
     *     ↓ JWKSource                 <br/>
     *   框架可查询的密钥来源             <br/>
     *<br/>
     *   类型                   含义 <br/>
     * `RSAKey`            用 JWK 模型表示一把 RSA 密钥 <br/>
     * `JWKSet`            一组 JWK <br/>
     * `JWKSource`         按条件查询密钥的来源 <br/>
     * `ImmutableJWKSet`   从固定密钥集合中选择密钥 <br/>
     */
    @Bean
    JWKSource<SecurityContext> jwkSource(
        @Value("${IDENTITY_SIGNING_KEYSTORE}") String file,
        @Value("${IDENTITY_SIGNING_KEYSTORE_PASSWORD}") String password)
        throws Exception {
        log.info("======开始加载RSA密钥======");
        char[] keystorePassword = password.toCharArray();
        // 第一步，读取文件
        try (InputStream input = Files.newInputStream(Path.of(file));) {
            // 第二步，打开密钥容器
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, keystorePassword);
            // 第三步，按 alias 找到 RSA 密钥
            RSAKey loadedKey = RSAKey.load(keyStore, KEY_ALIAS, keystorePassword);
            if (loadedKey == null || !loadedKey.isPrivate()) {
                throw new IllegalStateException(
                    "签名密钥不存在，或未包含RSA私钥");
            }

            if (loadedKey.size() < 2048) {
                throw new IllegalStateException(
                    "RSA签名密钥长度不得小于2048位");
            }
            log.info("加载RSA密钥文件成功");
            // 第四步，转换成框架使用的密钥来源
            // 同一把密钥始终使用同一kid，不在启动时生成随机标识。
            RSAKey signingKey  = new RSAKey.Builder(loadedKey)
                .keyID(KEY_ALIAS)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
            log.info("======创建RSA密钥完成======");
            return new ImmutableJWKSet<>(new JWKSet(signingKey));
        } finally {
            // 尽早清除临时字符数组；这不代表配置中的String也已被清除。
            Arrays.fill(keystorePassword, '\0');
        }
    }
}
