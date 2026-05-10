package com.cloudinfra.gateway.config;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class SentinelConfig {

    @PostConstruct
    public void init() {
        initGatewayRules();
    }

    private void initGatewayRules() {
        // 显式确保 Nacos 规则源在启动时即已加载
        // 实际加载由 spring-cloud-starter-alibaba-sentinel 自动处理
        // 此处作为扩展点，可添加自定义的规则预加载逻辑
    }
}
