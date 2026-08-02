package com.cloudinfra.gateway.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * API 文档聚合配置
 * 网关聚合所有微服务的 OpenAPI 文档，统一入口访问
 */
@Configuration
public class OpenApiAggregationConfig {

    /**
     * 系统服务文档
     */
    @Bean
    @Order(1)
    public GroupedOpenApi systemApi() {
        return GroupedOpenApi.builder()
            .group("system")
            .displayName("系统管理服务")
            .pathsToMatch("/api/system/**", "/system/**")
            .build();
    }

    /**
     * 认证服务文档
     */
    @Bean
    @Order(2)
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
            .group("auth")
            .displayName("认证服务")
            .pathsToMatch("/api/auth/**", "/auth/**")
            .build();
    }

    /**
     * 文件服务文档
     */
    @Bean
    @Order(3)
    public GroupedOpenApi fileApi() {
        return GroupedOpenApi.builder()
            .group("file")
            .displayName("文件服务")
            .pathsToMatch("/api/file/**", "/file/**")
            .build();
    }

    /**
     * 通知服务文档
     */
    @Bean
    @Order(4)
    public GroupedOpenApi notificationApi() {
        return GroupedOpenApi.builder()
            .group("notification")
            .displayName("通知服务")
            .pathsToMatch("/api/notification/**", "/notification/**")
            .build();
    }

    /**
     * 搜索服务文档
     */
    @Bean
    @Order(5)
    public GroupedOpenApi searchApi() {
        return GroupedOpenApi.builder()
            .group("search")
            .displayName("搜索服务")
            .pathsToMatch("/api/search/**", "/search/**")
            .build();
    }

    /**
     * 平台能力文档（错误码、健康检查等）
     */
    @Bean
    @Order(6)
    public GroupedOpenApi platformApi() {
        return GroupedOpenApi.builder()
            .group("platform")
            .displayName("平台能力")
            .pathsToMatch("/api/error-codes/**", "/api/health/**")
            .build();
    }

    /**
     * 全部 API 聚合文档
     */
    @Bean
    @Order(0)
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
            .group("all")
            .displayName("全部 API")
            .pathsToMatch("/api/**", "/system/**", "/auth/**", "/file/**", "/notification/**", "/search/**")
            .build();
    }
}