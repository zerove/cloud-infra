package com.cloudinfra.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

import jakarta.annotation.PostConstruct;

@Configuration
public class SentinelConfig {

    @PostConstruct
    public void init() {
        initBlockHandler();
        initGatewayRules();
    }

    private void initBlockHandler() {
        GatewayCallbackManager.setBlockHandler((exchange, throwable) -> {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"code\": 429, \"message\": \"Too Many Requests\"}");
        });
    }

    private void initGatewayRules() {
        // 显式确保 Nacos 规则源在启动时即已加载
        // 实际加载由 spring-cloud-starter-alibaba-sentinel 自动处理
        // 此处作为扩展点，可添加自定义的规则预加载逻辑
    }
}
