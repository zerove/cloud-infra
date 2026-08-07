package com.cloudinfra.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
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
    void shouldCreateCorsFilter() throws Exception {
        GatewayConfig config = new GatewayConfig();
        setAllowedOrigins(config, List.of("http://localhost:*"));
        CorsFilter corsFilter = config.corsFilter();
        assertThat(corsFilter).isNotNull();
    }

    @Test
    void shouldRegisterCorsMappings() throws Exception {
        GatewayConfig config = new GatewayConfig();
        setAllowedOrigins(config, List.of("http://localhost:*"));
        CorsRegistry registry = new CorsRegistry();
        // 不应抛异常
        config.addCorsMappings(registry);
    }

    private void setAllowedOrigins(GatewayConfig config, List<String> origins) throws Exception {
        Field field = GatewayConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        field.set(config, origins);
    }
}