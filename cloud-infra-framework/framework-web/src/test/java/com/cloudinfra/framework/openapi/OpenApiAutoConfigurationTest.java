package com.cloudinfra.framework.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenApiAutoConfiguration 单元测试 (M6-05)
 *
 * 覆盖 4 个关键场景:
 * - title 从 spring.application.name 推断 (cloud-system → "Cloud-Infra System")
 * - JWT Bearer 安全方案
 * - 默认 server URL
 * - Contact/License 元数据
 */
class OpenApiAutoConfigurationTest {

    private OpenApiAutoConfiguration config = new OpenApiAutoConfiguration();

    @Test
    @DisplayName("title 从 spring.application.name 推断 (cloud-system → 'Cloud-Infra System')")
    void shouldInferTitleFromAppName() {
        MockEnvironment env = new MockEnvironment();
        OpenAPI openApi = config.cloudInfraOpenApi(env, "cloud-system", "/", "1.0.0", "dev@x.com");

        Info info = openApi.getInfo();
        assertThat(info).isNotNull();
        assertThat(info.getTitle()).isEqualTo("Cloud-Infra System");
        assertThat(info.getDescription()).isEqualTo("Cloud-Infra System API Documentation");
        assertThat(info.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("title 推断: cloud-auth → 'Cloud-Infra Auth'")
    void shouldInferTitleForAuthModule() {
        MockEnvironment env = new MockEnvironment();
        OpenAPI openApi = config.cloudInfraOpenApi(env, "cloud-auth", "/", "1.0.0", "dev@x.com");

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Cloud-Infra Auth");
    }

    @Test
    @DisplayName("JWT Bearer security scheme 已注册")
    void shouldRegisterBearerAuthScheme() {
        MockEnvironment env = new MockEnvironment();
        OpenAPI openApi = config.cloudInfraOpenApi(env, "cloud-system", "/", "1.0.0", "dev@x.com");

        assertThat(openApi.getComponents()).isNotNull();
        assertThat(openApi.getComponents().getSecuritySchemes())
            .containsKey("BearerAuth");
        var scheme = openApi.getComponents().getSecuritySchemes().get("BearerAuth");
        assertThat(scheme.getType()).isEqualTo(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    @DisplayName("默认 server URL (可配置覆盖)")
    void shouldConfigureServerUrl() {
        MockEnvironment env = new MockEnvironment();
        OpenAPI openApi = config.cloudInfraOpenApi(env, "cloud-system", "https://api.example.com", "1.0.0", "dev@x.com");

        assertThat(openApi.getServers()).hasSize(1);
        assertThat(openApi.getServers().get(0).getUrl()).isEqualTo("https://api.example.com");
    }

    @Test
    @DisplayName("Contact + License 元数据填充")
    void shouldFillContactAndLicense() {
        MockEnvironment env = new MockEnvironment();
        OpenAPI openApi = config.cloudInfraOpenApi(env, "cloud-system", "/", "1.0.0", "team@x.com");

        Info info = openApi.getInfo();
        assertThat(info.getContact().getEmail()).isEqualTo("team@x.com");
        assertThat(info.getContact().getName()).isEqualTo("Cloud-Infra Team");
        assertThat(info.getLicense().getName()).isEqualTo("Apache 2.0");
    }

    @Test
    @DisplayName("SecurityRequirement 默认 apply BearerAuth 到所有 API")
    void shouldApplySecurityRequirement() {
        MockEnvironment env = new MockEnvironment();
        OpenAPI openApi = config.cloudInfraOpenApi(env, "cloud-system", "/", "1.0.0", "dev@x.com");

        assertThat(openApi.getSecurity()).hasSize(1);
        assertThat(openApi.getSecurity().get(0)).containsKey("BearerAuth");
    }
}