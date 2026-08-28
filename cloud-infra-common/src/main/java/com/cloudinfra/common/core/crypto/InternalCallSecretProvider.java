package com.cloudinfra.common.core.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内部调用签名密钥集中持有者（Spring Bean）。
 *
 * <p>集中注入 secret 替代散落在 6 处的 {@code @Value}，避免 jasypt 4.0.4 注入空 bug
 * 在每个使用点重复触发。</p>
 *
 * <p>注入链路：{@code @Autowired InternalCallSecretProvider} → Spring 容器
 * 在创建该 bean 时通过 {@link InternalCallCrypto#resolveSecret()} 读 env/property
 * （绕开 jasypt 包装路径）→ 后续业务代码通过 {@link #getSecret()} 获取。</p>
 *
 * <p>统一持有后，下游各模块（gateway SecurityFilter / auth FeignConfig /
 * system SystemSecurityConfig / framework-security HeaderAuthenticationFilter /
 * starter FeignTraceIdAutoConfiguration）都引用此 bean，
 * 配置变更/诊断时只需关注一处。</p>
 */
@Component
public class InternalCallSecretProvider {

    private static final Logger log = LoggerFactory.getLogger(InternalCallSecretProvider.class);

    private volatile String secret;

    @PostConstruct
    public void init() {
        this.secret = InternalCallCrypto.resolveSecret();
        log.info("内部调用签名密钥已加载, 长度={}", secret == null ? 0 : secret.length());
    }

    /**
     * 获取内部调用签名密钥。
     * <p>如果返回 null/空串, 调用方应视为配置缺失, 不可用于签名/验签。
     */
    public String getSecret() {
        return secret;
    }
}
