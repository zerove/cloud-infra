package com.cloudinfra.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 网关配置：WebMVC 配置
 * 注意：Spring Cloud Gateway MVC 使用 spring.cloud.gateway.mvc.cors 配置跨域
 * 此处保留 WebMvcConfigurer 仅用于其他 MVC 配置（如静态资源等）
 */
@Configuration
public class GatewayConfig implements WebMvcConfigurer {

    /**
     * 允许跨域的来源（逗号分隔）。
     * 注意：浏览器规范禁止 allowCredentials=true 与通配符来源并存，
     * 因此必须显式配置来源清单；默认放开常见本地开发源。
     * 实际 CORS 配置由 spring.cloud.gateway.mvc.cors 控制（见 deploy/config/gateway.yaml）
     */
    @Value("${gateway.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private List<String> allowedOrigins;
}