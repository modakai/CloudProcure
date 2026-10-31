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
 * 签名密钥配置：OIDC 的信任根。
 *
 * <p>为什么必须持久化：客户端（门户/资源服务）会缓存 JWKS 公钥并用它验签。
 * 如果每次启动都生成新密钥，已签发的 Token 全部验不过，客户端还会因为 kid 变化反复拉 JWKS；
 * 生产轮换必须“先发布新公钥、保留旧公钥到旧 Token 全部过期”，不能靠重启换 key。
 *
 * <p>为什么缺密钥就启动失败：静默生成替代密钥会让故障延后到“Token 验不过”才暴露，
 * 既难排查，也容易被误判成客户端问题。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class SigningKeyConfiguration {

    // PKCS12 条目别名，同时用作 JWKS 里的 kid。
    // 轮换时新增 alias（例如 identity-signing-v2），旧 alias 保留到旧 Token 全部过期；
    // 不要复用同一个 alias 覆盖私钥，否则旧 Token 会突然验不过。
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
        // 口令先转 char[]：String 不可变，无法在内存里擦除；char[] 用完可以清零。
        char[] keystorePassword = password.toCharArray();
        // 第一步，读取文件
        try (InputStream input = Files.newInputStream(Path.of(file));) {
            // 第二步，打开密钥容器。PKCS12 是密钥库容器格式，不是密钥本身；load 需要容器口令。
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, keystorePassword);
            // 第三步，按 alias 从容器里取出条目，并解析成 Nimbus 的 RSAKey（包含私钥指数 d）。
            RSAKey loadedKey = RSAKey.load(keyStore, KEY_ALIAS, keystorePassword);
            if (loadedKey == null || !loadedKey.isPrivate()) {
                // 只有公钥/证书时不能签名，必须报错，而不是继续启动一个“签不出 Token”的服务。
                throw new IllegalStateException(
                    "签名密钥不存在，或未包含RSA私钥");
            }

            if (loadedKey.size() < 2048) {
                // 2048 位是当前 RS256 的常见下限；更短的密钥不适合作为身份签发密钥。
                throw new IllegalStateException(
                    "RSA签名密钥长度不得小于2048位");
            }
            log.info("加载RSA密钥文件成功");
            // 第四步，转换成框架使用的密钥来源。
            // 同一把密钥始终使用同一 kid，不在启动时生成随机标识：
            // Token header 里的 kid 与 JWKS 里的 kid 必须能对上。
            // keyUse/keyAlg 是 JWKS 元数据：告诉客户端“这把 key 用于 RS256 验签”，
            // 也避免它被误当作加密密钥使用。
            RSAKey signingKey  = new RSAKey.Builder(loadedKey)
                .keyID(KEY_ALIAS)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
            log.info("======创建RSA密钥完成======");
            // 启动后 key 集合不可变：单 key 场景够用。
            // 若要做平滑轮换（新旧公钥同时发布一段时间），这里需要改成可变的 JWKSource。
            return new ImmutableJWKSet<>(new JWKSet(signingKey));
        } finally {
            // 尽早清除临时字符数组；这不代表配置中的String也已被清除。
            Arrays.fill(keystorePassword, '\0');
        }
    }
}
