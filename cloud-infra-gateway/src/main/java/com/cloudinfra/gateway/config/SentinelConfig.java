package com.cloudinfra.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.cloudinfra.common.dto.Result;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;

@Configuration
public class SentinelConfig {

    @PostConstruct
    public void init() {
        GatewayCallbackManager.setBlockHandler((exchange, throwable) -> {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"code\": 429, \"message\": \"Too Many Requests\"}");
        });
    }
}
