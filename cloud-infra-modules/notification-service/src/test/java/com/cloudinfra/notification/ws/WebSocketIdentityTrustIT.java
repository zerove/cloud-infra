package com.cloudinfra.notification.ws;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.notification.config.WebSocketConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
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
 * WebSocket 身份信任边界集成验证 (M7-03 生产入口验收前置)
 *
 * <p>以真实 TLS 端点 + 真实 WebSocket 客户端, 验证 {@link WebSocketAuthFilter}
 * 的信任模型与 HTTP 侧对齐。</p>
 *
 * <p><b>2026-09-29 修复前</b>: 该 Filter 只校验 {@code X-User-Id} 是否<b>存在</b>,
 * 实测确认携带任意 {@code X-User-Id: 999999} 且无任何签名即可建立连接
 * —— 任何能直连本服务端口的一方都能冒充该用户。而 HTTP 侧的对应机制
 * ({@code SystemSecurityConfig.internalCallMatcher} 与
 * {@code HeaderAuthenticationFilter.isTrustedInternalRequest}) 会用
 * {@code InternalCallCrypto} 校验 HMAC 签名, 两处信任模型不一致。</p>
 *
 * <p><b>2026-09-29 修复后</b>: 开启 {@code cloud-infra.security.verify-internal-sign} 时,
 * 无签名/伪造签名一律拒于握手前; 网关注入的有效签名方可建立连接。
 * 本 IT 断言的是<b>修复后的期望行为</b>。</p>
 */
class WebSocketIdentityTrustIT {

    private static final String SECRET = "ws-it-internal-secret";

    @TempDir
    Path temporary;

    /**
     * 清理本 IT 设置的系统属性 —— 否则会污染同一 JVM 内后续 IT
     * (如 {@code WebSocketIntegrationTest} 不发签名, 会被误拒)。
     */
    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("cloud-infra.security.verify-internal-sign");
        System.clearProperty("cloud-infra.internal-call.secret");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({SslAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
            // @Value 占位符解析所需: WebSocketAuthFilter 的 verify-internal-sign 依赖它
            org.springframework.boot.autoconfigure.context
                .PropertyPlaceholderAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthFilter.class, NotificationWebSocketHandler.class})
    static class WsTestConfiguration {

        @Bean
        InternalCallSecretProvider internalCallSecretProvider() {
            InternalCallSecretProvider provider = new InternalCallSecretProvider();
            provider.init();
            return provider;
        }
    }

    @Test
    @DisplayName("修复后: 无签名的伪造 X-User-Id 被拒于握手前")
    void forgedUserIdWithoutSignatureIsRejected() throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = generateCertificate(password);
        SSLContext ssl = trustContext();

        try (var context = startServer(password, store);
             HttpClient client = HttpClient.newBuilder().sslContext(ssl)
                     .connectTimeout(Duration.ofSeconds(5)).build()) {

            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("wss://localhost:" + port + "/notification/ws");

            // 攻击者: 绕过网关直连, 编造他人 userId, 不带任何网关签名
            ExecutionException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> client.newWebSocketBuilder()
                    .header("X-User-Id", "999999")
                    .buildAsync(endpoint, new NoopListener()).get(10, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                java.net.http.WebSocketHandshakeException.class, rejected.getCause());
        }
    }

    @Test
    @DisplayName("修复后: 网关注入的有效签名可正常建立连接")
    void validGatewaySignatureIsAccepted() throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = generateCertificate(password);
        SSLContext ssl = trustContext();

        try (var context = startServer(password, store);
             HttpClient client = HttpClient.newBuilder().sslContext(ssl)
                     .connectTimeout(Duration.ofSeconds(5)).build()) {

            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("wss://localhost:" + port + "/notification/ws");

            long timestamp = System.currentTimeMillis();
            String nonce = "it-nonce-" + timestamp;
            var builder = client.newWebSocketBuilder()
                .header("X-User-Id", "1001")
                .header(InternalCallCrypto.HEADER_SIGN,
                    InternalCallCrypto.sign(SECRET, timestamp, nonce))
                .header(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(InternalCallCrypto.HEADER_NONCE, nonce);

            WebSocket socket = builder.buildAsync(endpoint, new NoopListener())
                .get(10, TimeUnit.SECONDS);
            // 必须主动断开: 连接保持打开时 context.close() 会一直等待, 导致测试挂起
            socket.abort();
        }
    }

    @Test
    @DisplayName("修复后: 缺少 X-User-Id 时被拒 (已实现的行为未回归)")
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
        // 验签开启: 该 IT 断言的是"修复后"的期望行为
        System.setProperty("cloud-infra.security.verify-internal-sign", "true");
        System.setProperty("cloud-infra.internal-call.secret", SECRET);
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
