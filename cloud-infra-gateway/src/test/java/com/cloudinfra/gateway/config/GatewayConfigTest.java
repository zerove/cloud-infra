package com.cloudinfra.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 网关配置单元测试
 * 验证 CORS 与 Web MVC 配置正确性
 */
class GatewayConfigTest {

    @Test
    void shouldImplementWebMvcConfigurer() {
        GatewayConfig config = new GatewayConfig();
        assertThat(config).isInstanceOf(WebMvcConfigurer.class);
    }

    @Test
    void shouldCreateCorsFilter() {
        GatewayConfig config = new GatewayConfig();
        CorsFilter corsFilter = config.corsFilter();
        assertThat(corsFilter).isNotNull();
    }

    @Test
    void shouldRegisterCorsMappings() {
        GatewayConfig config = new GatewayConfig();
        CorsRegistry registry = new CorsRegistry();
        // 不应抛异常
        config.addCorsMappings(registry);
    }
}