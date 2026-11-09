package com.cloudinfrait.system.plugin;

import com.cloudinfra.system.controller.PluginController;
import com.cloudinfra.system.plugin.PluginContextImpl;
import com.cloudinfra.system.plugin.PluginManager;
import com.cloudinfra.system.plugin.PluginRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@link PluginController} 与 {@link PluginManager} 的条件装配一致性。
 *
 * <p>背景（2026-11-09 运行时实证）: {@code deploy/config/system-module.yaml} 自
 * 2026-10-15（{@code b6b65b27}，插件系统 fail-closed）起把
 * {@code cloud-infra.plugin.enabled} 设为 {@code false}，且 Nacos 配置优先级高于
 * {@code application-dev.yml}。而 {@code PluginManager} 自 2026-08-29（ADR-010）起
 * 带 {@code @ConditionalOnProperty(..., matchIfMissing = true)}，控制器却没有同条件，
 * 导致 system-module 在干净配置下启动即失败：
 * <pre>
 * Parameter 0 of constructor in com.cloudinfra.system.controller.PluginController
 * required a bean of type 'com.cloudinfra.system.plugin.PluginManager' that could not be found.
 * </pre>
 * 本地曾用 {@code podman update -e cloud-infra.plugin.enabled=true} 掩盖，CI 一旦真跑
 * 就会在系统服务启动阶段失败。
 *
 * <p>守护方式: 用真实 Spring 装配断言"禁用时上下文能启动且控制器/管理器都不存在"，
 * 以及"启用或未设置时两者都存在"。仅断言注解字符串无法覆盖条件语义。
 *
 * <p>夹具放在 {@code com.cloudinfrait.*}（不在 {@code com.cloudinfra} 扫描范围内），
 * 避免静态嵌套配置被 {@code SystemApplication} 的组件扫描带到其他测试上下文。
 */
@DisplayName("PluginController/PluginManager 条件装配一致性")
class PluginControllerConditionalWiringTest {

    @Configuration
    @Import({PluginController.class, PluginManager.class, PluginContextImpl.class, PluginRegistry.class})
    static class PluginSlice {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PluginSlice.class);

    @Test
    @DisplayName("plugin.enabled=false（Nacos 默认 fail-closed）时上下文必须能启动")
    void contextStartsWhenPluginDisabled() {
        runner.withPropertyValues("cloud-infra.plugin.enabled=false")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(PluginManager.class);
                assertThat(ctx).doesNotHaveBean(PluginController.class);
            });
    }

    @Test
    @DisplayName("plugin.enabled=true（dev 显式启用）时控制器与管理器都装配")
    void controllerWiredWhenPluginEnabled() {
        runner.withPropertyValues("cloud-infra.plugin.enabled=true")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(PluginManager.class);
                assertThat(ctx).hasSingleBean(PluginController.class);
            });
    }

    @Test
    @DisplayName("未设置该属性时保持默认（matchIfMissing=true，与 PluginManager 一致）")
    void defaultWiresBoth() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(PluginManager.class);
            assertThat(ctx).hasSingleBean(PluginController.class);
        });
    }
}
