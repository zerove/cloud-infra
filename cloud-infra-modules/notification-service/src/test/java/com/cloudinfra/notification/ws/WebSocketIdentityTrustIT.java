package com.cloudinfra.notification.ws;

import com.cloudinfra.notification.config.WebSocketConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 身份信任边界实测 (M7-03 生产入口验收前置)
 *
 * <p>以真实 TLS 端点 + 真实 WebSocket 客户端, 实证 {@link WebSocketAuthFilter} 的
 * 信任模型, 为 "可信网关身份校验" 验收提供事实依据。</p>
 *
 * <p><b>实测结论</b>: 该 Filter 只校验 {@code X-User-Id} 是否<b>存在</b>,
 * 不校验其是否真由网关注入。而 HTTP 侧的对应机制
 * ({@code SystemSecurityConfig.internalCallMatcher} 与
 * {@code HeaderAuthenticationFilter.isTrustedInternalRequest}) 会用
 * {@code InternalCallCrypto} 校验 HMAC 签名 —— 两处信任模型不一致。</p>
 *
 * <p><b>潜在后果</b>: 任何能直连 notification-service 端口的一方, 仅需发送
 * {@code X-User-Id: <任意 ID>} 即可冒充该用户, 订阅其实时通知
 * ({@code pushToUser} 按 userId 推送)。是否构成实际风险取决于该端口是否仅对
 * 网关开放 (网络层隔离), 而这正是 M7-03 生产入口验收尚未验证的部分。</p>
 *
 * <p>本 IT 记录<b>当前行为</b>而非理想行为: 修复落地后应把
 * {@code anySuppliedUserIdIsAcceptedWithoutSignature} 翻转为断言拒绝。</p>
 */
class WebSocketIdentityTrustIT {

    @TempDir
    Path temporary;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({SslAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthFilter.class, NotificationWebSocketHandler.class})
    static class WsTestConfiguration {
    }

    @Test
    @DisplayName("现状实测: 任意 X-User-Id 均被接受 —— 头只验存在不验来源")
    void anySuppliedUserIdIsAcceptedWithoutSignature() throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = generateCertificate(password);
        SSLContext ssl = trustContext();

        try (var context = startServer(password, store);
             HttpClient client = HttpClient.newBuilder().sslContext(ssl)
                     .connectTimeout(Duration.ofSeconds(5)).build()) {

            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            // 模拟"绕过网关直连"的攻击者: 编造他人 userId, 不带任何网关签名
            URI endpoint = URI.create("wss://localhost:" + port + "/notification/ws");

            // 当前行为: 只要 X-User-Id 存在即放行 —— 这就是信任模型缺口
            WebSocket socket = client.newWebSocketBuilder()
                .header("X-User-Id", "999999")
                .buildAsync(endpoint, new NoopListener())
                .get(10, TimeUnit.SECONDS);
            // 必须主动断开: 连接保持打开时 context.close() 会一直等待, 导致测试挂起
            socket.abort();
        }
    }

    @Test
    @DisplayName("现状实测: 缺少 X-User-Id 时被拒 (已实现的部分)")
    void missingUserIdIsRejected() throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = generateCertificate(password);
        SSLContext ssl = trustContext();

        try (var context = startServer(password, store);
             HttpClient client = HttpClient.newBuilder().sslContext(ssl)
                     .connectTimeout(Duration.ofSeconds(5)).build()) {

            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("wss://localhost:" + port + "/notification/ws");

            ExecutionException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> client.newWebSocketBuilder()
                    .buildAsync(endpoint, new NoopListener()).get(10, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                java.net.http.WebSocketHandshakeException.class, rejected.getCause());
        }
    }

    // ========== 辅助 ==========

    private static final class NoopListener implements WebSocket.Listener {
        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return null;
        }
    }

    private Path generateCertificate(String password) throws Exception {
        Path script = Path.of("../../deploy/ssl/generate-notification-cert.sh").toAbsolutePath();
        ProcessBuilder generator = new ProcessBuilder("bash", script.toString());
        generator.environment().put("SSL_KEYSTORE_PASSWORD", password);
        generator.environment().put("SSL_OUTPUT_DIR", temporary.toString());
        generator.redirectErrorStream(true);
        generator.redirectOutput(temporary.resolve("cert.log").toFile());
        Process process = generator.start();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("证书生成失败: "
                + new String(Files.readAllBytes(temporary.resolve("cert.log"))));
        }
        return temporary.resolve("notification.p12");
    }

    private SSLContext trustContext() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("ws", java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(Files.newInputStream(temporary.resolve("server.crt"))));
        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return ssl;
    }

    /**
     * 用命令行参数启动 —— 其优先级高于 application.yml 中的 {@code server.port: 8084},
     * 否则会端口冲突。参数组合与 {@code WebSocketIntegrationTest} 保持一致。
     */
    private static org.springframework.context.ConfigurableApplicationContext startServer(
            String password, Path store) {
        return new SpringApplicationBuilder(WsTestConfiguration.class).run(
            "--spring.config.location=optional:classpath:/websocket-isolated-test.yml",
            "--spring.cloud.nacos.config.import-check.enabled=false",
            "--spring.main.web-application-type=servlet", "--server.port=0",
            "--server.address=127.0.0.1", "--server.ssl.enabled=true",
            "--server.ssl.key-store=" + store.toUri(), "--server.ssl.key-store-type=PKCS12",
            "--server.ssl.key-store-password=" + password,
            "--server.ssl.key-alias=notification",
            "--server.ssl.enabled-protocols=TLSv1.3,TLSv1.2");
    }
}
