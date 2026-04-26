package com.cloudinfra.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.cloudinfra.common.dto.Result;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SentinelConfig {

    @PostConstruct
    public void init() {
        GatewayCallbackManager.setBlockHandler((request, response, ex) -> {
            HttpServletResponse res = (HttpServletResponse) response;
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().println("{\"code\": 429, \"message\": \"Too Many Requests\"}");
        });
    }
}
