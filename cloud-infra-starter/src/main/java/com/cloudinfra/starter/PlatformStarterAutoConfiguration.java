package com.cloudinfra.starter;

import com.cloudinfra.common.core.tenant.TenantContextFilter;
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
     */
    @Bean
    @ConditionalOnProperty(prefix = "cloud-infra.tenant", name = "enabled", havingValue = "true", matchIfMissing = false)
    public TenantContextFilter tenantContextFilter(PlatformStarterProperties properties) {
        return new TenantContextFilter(properties.getTenant().isRequireTenant());
    }
}