package com.cloudinfra.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "com.alibaba.cloud.nacos.NacosConfigManager")
public class GatewayConfig {

    @Bean
    @ConditionalOnMissingBean(NacosConfigManager.class)
    public NacosConfigManager nacosConfigManager() {
        return new NacosConfigManager(null);
    }

    @Bean
    public DynamicRouteManager dynamicRouteManager(NacosConfigManager nacosConfigManager) {
        return new DynamicRouteManager(nacosConfigManager);
    }
}
