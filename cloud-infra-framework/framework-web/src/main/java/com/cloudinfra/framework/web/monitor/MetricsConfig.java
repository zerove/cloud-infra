package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.MeterRegistry;
// import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 度量指标配置
 * 为所有服务注册统一的公共标签，便于在 Prometheus/Grafana 中进行聚合分析
 */
/*
@Configuration
public class MetricsConfig {
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("project", "cloud-infra");
    }
}
*/
