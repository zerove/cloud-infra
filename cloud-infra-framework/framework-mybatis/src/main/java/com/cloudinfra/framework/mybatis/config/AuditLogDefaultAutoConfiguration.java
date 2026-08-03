package com.cloudinfra.framework.mybatis.config;

import com.cloudinfra.common.core.audit.DefaultAuditLogServiceImpl;
import com.cloudinfra.common.core.audit.IAuditLogService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 审计日志默认实现自动配置
 *
 * 当应用未提供自定义 IAuditLogService 实现时，装配 DefaultAuditLogServiceImpl。
 * 自动配置在组件扫描之后评估 @ConditionalOnMissingBean，因此应用自定义
 * 实现 (如 system-module 的 AuditLogServiceImpl) 会优先生效。
 */
@AutoConfiguration
public class AuditLogDefaultAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IAuditLogService.class)
    public IAuditLogService defaultAuditLogService() {
        return new DefaultAuditLogServiceImpl();
    }
}
