package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {
    @Bean
    public MeterFilter commonTags() {
        return MeterFilter.commonTags(java.util.List.of(
            io.micrometer.core.instrument.Tag.of("application", "cloud-infra")
        ));
    }
}
