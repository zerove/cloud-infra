package com.cloudinfra.gateway.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护网关转发超时**确实来自配置项**。
 *
 * <p>修复前的形态是：类里声明 {@code READ_TIMEOUT = ofSeconds(10)}，
 * 而 {@code RequestConfig} 里另写了一份字面量 {@code ofSeconds(10)}。
 * 两份互不引用 —— 改常量不会有任何效果，字面量才是实际生效的那个。
 * 这种"声明存在被当成生效"的缺陷，光看代码看不出来，必须由断言值来钉死。
 */
class GatewayHttpTimeoutWiringTest {

    private final GatewayClientConfig clientConfig = new GatewayClientConfig();

    /**
     * 取一组**故意不同于任何默认值**的时长：若实现退回硬编码，
     * 这三个断言必然失败，而不会被默认值蒙混过关。
     */
    @Test
    void requestConfigTakesEveryTimeoutFromProperties() {
        GatewayHttpTimeoutProperties props = new GatewayHttpTimeoutProperties();
        props.setRead(Duration.ofSeconds(77));
        props.setConnect(Duration.ofSeconds(5));
        props.setConnectionRequest(Duration.ofSeconds(6));

        RequestConfig config = clientConfig.buildRequestConfig(props);

        assertThat(config.getResponseTimeout().toDuration()).isEqualTo(Duration.ofSeconds(77));
        assertThat(config.getConnectTimeout().toDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getConnectionRequestTimeout().toDuration()).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void defaultReadTimeoutIsSixtySeconds() {
        assertThat(new GatewayHttpTimeoutProperties().getRead()).isEqualTo(Duration.ofSeconds(60));
    }

    /**
     * 键名必须逐字对齐：写 {@code readTimeout} 会**静默**退回默认值而不报错，
     * 运维改了配置却看不到任何效果。此处用 Binder 走真实绑定路径验证。
     */
    @Test
    void kebabCaseKeysBindToBeanProperties() {
        GatewayHttpTimeoutProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "gateway.http-timeout.read", "77s",
                "gateway.http-timeout.connect", "5s",
                "gateway.http-timeout.connection-request", "6s")))
                .bind("gateway.http-timeout", Bindable.of(GatewayHttpTimeoutProperties.class))
                .get();

        assertThat(props.getRead()).isEqualTo(Duration.ofSeconds(77));
        assertThat(props.getConnect()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getConnectionRequest()).isEqualTo(Duration.ofSeconds(6));
    }

    /**
     * 直接对 {@code deploy/config/gateway.yaml} 走真实解析与绑定，
     * 防止 yaml 里的键名与 Bean 属性漂移。文件不存在时跳过（模块内单测
     * 从仓库根运行时可能定位不到该路径），但在该路径可见时必须真的解析成功。
     */
    @Test
    void deployYamlActuallyBindsAndOverridesDefaults() throws IOException {
        Path yaml = locateDeployGatewayYaml();

        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("gateway", new FileSystemResource(yaml));

        GatewayHttpTimeoutProperties props = new Binder(
                ConfigurationPropertySources.from(sources.getFirst()))
                .bind("gateway.http-timeout", Bindable.of(GatewayHttpTimeoutProperties.class))
                .get();

        assertThat(props.getRead()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.getConnect()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.getConnectionRequest()).isEqualTo(Duration.ofSeconds(3));
    }

    /**
     * 找不到时**直接失败**而非跳过：该文件始终入库，跳过会让这条
     * yaml↔Bean 键名对齐的守护静默失效 —— 而"守护被跳过"是本项目反复记录的假绿。
     */
    private static Path locateDeployGatewayYaml() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; dir != null && i < 6; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy").resolve("config").resolve("gateway.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError(
                "未能定位 deploy/config/gateway.yaml (cwd=" + Path.of("").toAbsolutePath() + ")");
    }
}
