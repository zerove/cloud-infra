package com.cloudinfra.framework.tracing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    @Bean
    public io.micrometer.core.instrument.config.MeterFilter commonTagsFilter() {
        return io.micrometer.core.instrument.config.MeterFilter.commonTags(
                java.util.Collections.singletonList(
                        io.micrometer.core.instrument.Tag.of("application", "cloud-infra-service")));
    }
}
