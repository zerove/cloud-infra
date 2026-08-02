package com.cloudinfra.common.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 错误码注册中心测试
 */
class ErrorCodeRegistryTest {

    private ErrorCodeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ErrorCodeRegistry();
        registry.init();
    }

    @Test
    void shouldRegisterBuiltinErrorCodes() {
        assertThat(registry.get(1000)).isNotNull();
        assertThat(registry.get(1000).name()).isEqualTo("SYSTEM_ERROR");
        assertThat(registry.get(2001)).isNotNull();
        assertThat(registry.get(2001).message()).isEqualTo("用户不存在");
    }

    @Test
    void shouldRegisterCustomErrorCode() {
        registry.register(7001, "ORDER_NOT_FOUND", "订单不存在", "ORDER");
        var entry = registry.get(7001);
        assertThat(entry).isNotNull();
        assertThat(entry.module()).isEqualTo("ORDER");
    }

    @Test
    void shouldInferModuleFromCodePrefix() {
        registry.register(3001, "USER_DISABLED", "用户已禁用");
        assertThat(registry.get(3001).module()).isEqualTo("USER");
    }

    @Test
    void shouldGenerateMarkdownDocument() {
        String doc = registry.generateMarkdownDocument();
        assertThat(doc).contains("# Cloud-Infra 错误码文档");
        assertThat(doc).contains("SYSTEM_ERROR");
        assertThat(doc).contains("| 错误码 |");
    }

    @Test
    void shouldGetByModule() {
        var systemCodes = registry.getByModule("SYSTEM");
        assertThat(systemCodes).isNotEmpty();
        assertThat(systemCodes.values())
            .allMatch(entry -> entry.module().equals("SYSTEM"));
    }
}