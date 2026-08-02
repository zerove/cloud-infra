package com.cloudinfra.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 网关 API 文档聚合配置单元测试
 * 验证 OpenAPI 分组配置正确性
 */
class OpenApiAggregationConfigTest {

    @Test
    void shouldBeInstantiable() {
        OpenApiAggregationConfig config = new OpenApiAggregationConfig();
        assertThat(config).isNotNull();
    }

    @Test
    void shouldCreateSystemApiGroup() {
        OpenApiAggregationConfig config = new OpenApiAggregationConfig();
        var group = config.systemApi();
        assertThat(group).isNotNull();
        assertThat(group.getGroup()).isEqualTo("system");
    }

    @Test
    void shouldCreateAuthApiGroup() {
        OpenApiAggregationConfig config = new OpenApiAggregationConfig();
        var group = config.authApi();
        assertThat(group).isNotNull();
        assertThat(group.getGroup()).isEqualTo("auth");
    }

    @Test
    void shouldCreateAllApiGroup() {
        OpenApiAggregationConfig config = new OpenApiAggregationConfig();
        var group = config.allApi();
        assertThat(group).isNotNull();
        assertThat(group.getGroup()).isEqualTo("all");
    }
}