package com.cloudinfra.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护网关转发超时真的由 {@link GatewayHttpTimeoutProperties} 驱动。
 *
 * <p>本测试存在的理由是一个已真实发生的缺陷：{@code GatewayClientConfig} 曾声明
 * {@code READ_TIMEOUT = ofSeconds(10)} 常量，同时在 {@code RequestConfig} 里另写一份
 * 字面量 {@code ofSeconds(10)}，两者互不引用。于是"改那个常量"对运行时毫无作用，
 * 而实际生效的一直是字面量 —— 该常量自建立起从未被读取过。
 *
 * <p>因此断言必须落在<b>最终装配出的 {@code RequestConfig}</b> 上：
 * 只断言属性类存在、只断言属性被绑定，都无法发现上述缺陷。
 *
 * <p>装置说明：这里刻意用 {@link ApplicationContextRunner} 起最小上下文，
 * 而不是 {@code @SpringBootTest}。后者会拉起整个 {@code GatewayApplication}，
 * 连带 Nacos 远程配置；而 Nacos 在单测 JVM 里不可达（{@code UNAVAILABLE: Network closed}），
 * 会让本测试因装置故障而红 —— 那是探针失效，不是被守护对象失败。
 *
 * <p>负向对照：把 {@code clientHttpRequestFactory} 改回读字面量 {@code ofSeconds(10)} 后，
 * {@link #responseTimeoutFollowsConfiguredValue()} 必定报红；恢复后转绿。
 */
class GatewayTimeoutPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(
                    GatewayHttpTimeoutProperties.class,
                    GatewayClientConfig.class,
                    ObservationRegistryStub.class);

    /**
     * {@code GatewayClientConfig.gatewayRestClientBuilder} 需要 {@code ObservationRegistry}，
     * 而本测试刻意不启动完整自动配置。给一个真实实例即可（它只是个空注册表）。
     */
    static class ObservationRegistryStub {
        @org.springframework.context.annotation.Bean
        io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }
    }

    @Test
    @DisplayName("配置项的值被正确绑定: 不能出现键名拼错却静默用默认值")
    void configurationValuesAreBound() {
        runner.withPropertyValues(
                        "gateway.http-timeout.read=77s",
                        "gateway.http-timeout.connect=5s",
                        "gateway.http-timeout.connection-request=6s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GatewayHttpTimeoutProperties props = context.getBean(GatewayHttpTimeoutProperties.class);
                    assertThat(props.getRead()).isEqualTo(Duration.ofSeconds(77));
                    assertThat(props.getConnect()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(props.getConnectionRequest()).isEqualTo(Duration.ofSeconds(6));
                });
    }

    @Test
    @DisplayName("未配置时读超时默认为 60s: 原 10s 余量不足以覆盖大文件上传")
    void readTimeoutDefaultsToSixtySeconds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GatewayHttpTimeoutProperties.class).getRead())
                    .isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    @DisplayName("响应超时真的来自配置项: 装配进 RequestConfig 的值必须随配置变化")
    void responseTimeoutFollowsConfiguredValue() {
        runner.withPropertyValues("gateway.http-timeout.read=77s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object requestConfig = extractRequestConfig(context);
                    assertThat(readTimeoutField(requestConfig, "responseTimeout"))
                            .as("RequestConfig 中的响应超时必须等于配置的 77s；若仍为 10s，说明装配处读的是硬编码字面量")
                            .isEqualTo(Duration.ofSeconds(77));
                });
    }

    @Test
    @DisplayName("连接与连接池借用超时同样来自配置项")
    void connectTimeoutsFollowConfiguredValue() {
        runner.withPropertyValues(
                        "gateway.http-timeout.connect=5s",
                        "gateway.http-timeout.connection-request=6s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object requestConfig = extractRequestConfig(context);
                    assertThat(readTimeoutField(requestConfig, "connectTimeout"))
                            .isEqualTo(Duration.ofSeconds(5));
                    assertThat(readTimeoutField(requestConfig, "connectionRequestTimeout"))
                            .isEqualTo(Duration.ofSeconds(6));
                });
    }

    @Test
    @DisplayName("配置 77s 与配置 3s 必须装配出不同的值: 防止装配处忽略入参")
    void differentConfigurationProducesDifferentTimeout() {
        Duration with77 = captureResponseTimeout("gateway.http-timeout.read=77s");
        Duration with3 = captureResponseTimeout("gateway.http-timeout.read=3s");

        assertThat(with77)
                .as("两个不同配置必须装配出不同的响应超时")
                .isNotEqualTo(with3);
        assertThat(with77).isEqualTo(Duration.ofSeconds(77));
        assertThat(with3).isEqualTo(Duration.ofSeconds(3));
    }

    private Duration captureResponseTimeout(String... properties) {
        Duration[] captured = new Duration[1];
        runner.withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    captured[0] = readTimeoutField(extractRequestConfig(context), "responseTimeout");
                });
        return captured[0];
    }

    /**
     * 从 {@code HttpComponentsClientHttpRequestFactory} 里取出底层 {@code RequestConfig}。
     */
    private static Object extractRequestConfig(org.springframework.context.ApplicationContext context) {
        Object factory = context.getBean(org.springframework.http.client.ClientHttpRequestFactory.class);
        assertThat(factory)
                .as("装配出的 requestFactory 应为 HttpComponentsClientHttpRequestFactory，实际为 %s",
                        factory.getClass().getName())
                .isInstanceOf(org.springframework.http.client.HttpComponentsClientHttpRequestFactory.class);
        try {
            return readField(readField(factory, "httpClient"), "defaultConfig");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法反射取出 RequestConfig，测试装置失效", e);
        }
    }

    private static Duration readTimeoutField(Object requestConfig, String fieldName) {
        try {
            Field field = findField(requestConfig.getClass(), fieldName);
            assertThat(field)
                    .as("RequestConfig 上应存在字段 %s（HttpClient 版本变化会改字段名，此时需同步本测试）", fieldName)
                    .isNotNull();
            field.setAccessible(true);
            Object value = field.get(requestConfig);
            assertThat(value)
                    .as("超时字段 %s 的类型应为 org.apache.hc.core5.util.Timeout，实际为 %s",
                            fieldName, value == null ? "null" : value.getClass().getName())
                    .isInstanceOf(org.apache.hc.core5.util.Timeout.class);
            return Duration.ofMillis(((org.apache.hc.core5.util.Timeout) value).toMilliseconds());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射读取超时字段失败，测试装置失效", e);
        }
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        assertThat(field)
                .as("%s 上应存在字段 %s", target.getClass().getSimpleName(), name)
                .isNotNull();
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        return null;
    }
}