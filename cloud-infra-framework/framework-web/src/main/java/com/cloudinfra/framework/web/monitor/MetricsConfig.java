package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 度量指标配置 - 2026-09-01 启用 (M5-03 业务告警指标接入)
 *
 * <p>业务级 Counter 不在此硬编码, 由 {@link BusinessMetricAspect} 按需动态创建:
 * <ul>
 *   <li>方法标注 {@link BusinessMetric} 即可自动注册指标</li>
 *   <li>Counter 按 (metric, tagKey, tagValue) 缓存避免重复 register</li>
 * </ul>
 */
@Configuration
public class MetricsConfig {

    @Bean
    @ConditionalOnMissingBean(name = "metricsCommonTags")
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("project", "cloud-infra");
    }
}
