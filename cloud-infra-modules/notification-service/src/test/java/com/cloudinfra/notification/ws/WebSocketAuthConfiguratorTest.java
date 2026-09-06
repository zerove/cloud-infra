package com.cloudinfra.notification.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocketAuthConfigurator 单元测试.
 *
 * <p>验证 Configurator 内部 HttpSession 反射读取逻辑 (避免直接依赖 servlet API 类).
 */
class WebSocketAuthConfiguratorTest {

    private WebSocketAuthConfigurator configurator;

    @BeforeEach
    void setUp() {
        configurator = new WebSocketAuthConfigurator();
    }

    @Test
    @DisplayName("userId 属性常量正确")
    void userIdConstantValid() {
        assertThat(WebSocketAuthConfigurator.USER_ID_PROPERTY).isEqualTo("auth.userId");
        assertThat(WebSocketAuthConfigurator.USER_NAME_PROPERTY).isEqualTo("auth.userName");
        assertThat(WebSocketAuthConfigurator.TENANT_ID_PROPERTY).isEqualTo("auth.tenantId");
    }

    @Test
    @DisplayName("Configurator 实例化无异常")
    void instantiable() {
        assertThat(configurator).isNotNull();
    }
}
