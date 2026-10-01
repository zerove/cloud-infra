package com.cloudinfra.starter;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.common.core.tenant.TenantContextFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 平台 Starter 自动装配
 * 聚合多租户、错误码、Outbox 等平台能力
 */
@AutoConfiguration
@EnableConfigurationProperties(PlatformStarterProperties.class)
public class PlatformStarterAutoConfiguration {

    /**
     * 租户上下文过滤器
     * 通过 cloud-infra.tenant.enabled 控制是否启用
     *
     * <p>注入 {@link InternalCallSecretProvider} 的密钥，使租户头与
     * {@code HeaderAuthenticationFilter} 走同一道内部签名边界：
     * 网关注入的租户可被信任，客户端裸传（绕过网关直连）的租户头被拒绝。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "cloud-infra.tenant", name = "enabled", havingValue = "true", matchIfMissing = false)
    public TenantContextFilter tenantContextFilter(PlatformStarterProperties properties,
                                                   ObjectProvider<InternalCallSecretProvider> secretProvider) {
        InternalCallSecretProvider provider = secretProvider.getIfAvailable();
        // provider 缺失时传 null: TenantContextFilter 对 null 密钥按 fail-closed 拒绝租户头,
        // 而不是放行任意租户。宁可拒绝, 不可静默失去隔离。
        String secret = provider == null ? null : provider.getSecret();
        return new TenantContextFilter(properties.getTenant().isRequireTenant(), secret);
    }
}