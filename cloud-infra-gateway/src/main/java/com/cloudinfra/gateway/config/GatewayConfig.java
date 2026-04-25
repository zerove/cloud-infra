package com.cloudinfra.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.cloudinfra.gateway.handler.GatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {
    @PostConstruct
    public void init() {
        GatewayCallbackManager.setBlockHandler(new GatewayBlockExceptionHandler());
    }
}
