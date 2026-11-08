package com.cloudinfra.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 结构性回归守护：网关不得将 {@code /internal/**} 路由到下游业务服务。
 *
 * <p>内部契约端点（如 {@code /internal/notification/logs}）依赖 HMAC 单点决策，
 * 且前提是网关无对应路由；若未来误加路由，会绕过“网关不转发内部路径”的边界假设。</p>
 *
 * <p>本测试为 L1/L2 静态守护，不能替代真实网关请求的 L3 运行时证据。</p>
 */
class InternalRouteExposureGuardTest {

    @Test
    void gatewayRouter_mustNotExposeInternalPaths() throws IOException {
        String source = readGatewayApplicationSource();

        assertThat(source)
                .as("网关路由定义必须存在")
                .contains("gatewayRouter", ".path(\"/notification/**\"");
        assertThat(source)
                .as("网关不得路由 /internal/** 到下游（内部契约仅服务间 HMAC 调用）")
                .doesNotContain(".path(\"/internal")
                .doesNotContain("\"/internal/**\"");
    }

    @Test
    void gatewayRouter_onlyRoutesExpectedPublicPrefixes() throws IOException {
        String source = readGatewayApplicationSource();
        // 确保当前已知公开前缀仍在；新增路由时需同步评估是否可能暴露内部路径。
        for (String prefix : List.of("/auth/**", "/system/**", "/file/**", "/notification/**", "/search/**")) {
            assertThat(source).as("应包含公开路由前缀 %s", prefix).contains(".path(\"" + prefix + "\"");
        }
    }

    private static String readGatewayApplicationSource() throws IOException {
        List<Path> candidates = List.of(
                Path.of("src/main/java/com/cloudinfra/gateway/GatewayApplication.java"),
                Path.of("cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/GatewayApplication.java"),
                Path.of("../cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/GatewayApplication.java")
        );
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("无法定位 GatewayApplication.java，已尝试: " + candidates
                + " (user.dir=" + System.getProperty("user.dir") + ")");
    }
}
