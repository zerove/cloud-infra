package com.cloudinfra.starter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RetryAutoConfiguration {
    // 可以在此配置调度器 Bean
}
