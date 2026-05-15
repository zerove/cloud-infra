package com.cloudinfra.gateway.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.fastjson2.JSON;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SentinelGatewayConfig {

    @Bean
    public Converter<String, List<GatewayFlowRule>> sentinelJsonGwFlowConverter() {
        return source -> JSON.parseArray(source, GatewayFlowRule.class);
    }
}
