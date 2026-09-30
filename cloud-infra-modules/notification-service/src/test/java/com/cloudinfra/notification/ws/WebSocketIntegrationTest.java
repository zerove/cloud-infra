package com.cloudinfra.notification.ws;

import com.cloudinfra.notification.config.WebSocketConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 隔离的真实生产 WebSocket 组件测试：Spring 自动装配 TLS 与端点，不手工注册测试服务。
 * 不包含 Nacos、网关、完整安全链或容器部署，不能代替生产入口验收。
 */
class WebSocketIntegrationTest {
    @TempDir
    Path temporary;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({SslAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
            // 本测试用 @ImportAutoConfiguration 显式圈定自动配置清单, 不含占位符解析器,
            // 于是 @Value("${...:默认值}") 不会被解析 —— 默认值只在能解析时生效,
            // 字面量会被直接注入 boolean 字段并抛 TypeMismatchException。
            // PropertyPlaceholderAutoConfiguration 提供 PropertySourcesPlaceholderConfigurer。
            PropertyPlaceholderAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthFilter.class, NotificationWebSocketHandler.class})
    static class WebSocketTestConfiguration { }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void tlsHandshakeHeartbeatPushAndCleanup(String protocol) throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = temporary.resolve("notification.p12");
        Path script = Path.of("../../deploy/ssl/generate-notification-cert.sh").toAbsolutePath();
        ProcessBuilder generator = new ProcessBuilder("bash", script.toString());
        generator.environment().put("SSL_KEYSTORE_PASSWORD", password);
        generator.environment().put("SSL_OUTPUT_DIR", temporary.toString());
        generator.redirectErrorStream(true).redirectOutput(temporary.resolve("certificate.log").toFile());
        Process process = generator.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "证书生成超时");
        assertEquals(0, process.exitValue(), "部署证书脚本执行失败");
        byte[] originalStore = Files.readAllBytes(store);
        Process repeated = generator.start();
        assertTrue(repeated.waitFor(30, TimeUnit.SECONDS), "重复生成检查超时");
        assertNotEquals(0, repeated.exitValue(), "必须拒绝覆盖已有证书");
        assertArrayEquals(originalStore, Files.readAllBytes(store));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keyStore.load(input, password.toCharArray());
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("notification", keyStore.getCertificate("notification"));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setProtocols(new String[]{protocol});

        try (var context = new SpringApplicationBuilder(WebSocketTestConfiguration.class).run(
                "--spring.config.location=optional:classpath:/websocket-isolated-test.yml",
                "--spring.cloud.nacos.config.import-check.enabled=false",
                "--spring.main.web-application-type=servlet", "--server.port=0",
                "--server.address=127.0.0.1", "--server.ssl.enabled=true",
                "--server.ssl.key-store=" + store.toUri(), "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-store-password=" + password,
                "--server.ssl.key-alias=notification",
                "--server.ssl.enabled-protocols=TLSv1.3,TLSv1.2");
             HttpClient client = HttpClient.newBuilder().sslContext(ssl).sslParameters(sslParameters)
                     .connectTimeout(Duration.ofSeconds(5)).build()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("wss://localhost:" + port + "/notification/ws");
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> client.newWebSocketBuilder().buildAsync(endpoint, new Messages()).get(10, TimeUnit.SECONDS));
            assertInstanceOf(WebSocketHandshakeException.class, rejected.getCause());
            assertEquals(401, ((WebSocketHandshakeException) rejected.getCause()).getResponse().statusCode());

            Messages listener = new Messages();
            WebSocket socket = client.newWebSocketBuilder().header("X-User-Id", "1001")
                    .buildAsync(endpoint, listener).get(10, TimeUnit.SECONDS);
            try {
                var connected = new ObjectMapper().readTree(listener.next());
                assertEquals("connected", connected.path("type").asText());
                assertEquals(1001, connected.path("userId").asLong());
                assertEquals(1, NotificationWebSocketHandler.activeConnectionCount());
                socket.sendText("ping", true).get(5, TimeUnit.SECONDS);
                assertEquals("pong", new ObjectMapper().readTree(listener.next()).path("type").asText());
                assertEquals(1, NotificationWebSocketHandler.pushToUser(1001L, Map.of("message", "TLS通知")));
                var pushed = new ObjectMapper().readTree(listener.next());
                assertEquals("notification", pushed.path("type").asText());
                assertEquals("TLS通知", pushed.path("payload").path("message").asText());
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "完成").get(5, TimeUnit.SECONDS);
                listener.closed.get(5, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertEquals(0, NotificationWebSocketHandler.activeConnectionCount());
                    assertEquals(0, NotificationWebSocketHandler.activeUserCount());
                });
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void certificateScriptRejectsMissingOrShortPassword() throws Exception {
        for (String password : new String[]{"", "short"}) {
            ProcessBuilder generator = new ProcessBuilder("bash",
                    Path.of("../../deploy/ssl/generate-notification-cert.sh").toAbsolutePath().toString());
            generator.environment().put("SSL_KEYSTORE_PASSWORD", password);
            generator.environment().put("SSL_OUTPUT_DIR", temporary.toString());
            generator.redirectErrorStream(true).redirectOutput(temporary.resolve("rejected.log").toFile());
            Process process = generator.start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertFalse(Files.exists(temporary.resolve("notification.p12")));
        }
    }

    private static class Messages implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder fragment = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragment.append(data);
            if (last) {
                messages.add(fragment.toString());
                fragment.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        String next() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "未在 5 秒内收到消息");
            return message;
        }
    }
}
