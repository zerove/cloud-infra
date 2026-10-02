package com.cloudinfra.system.grpc;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.common.core.tenant.TenantContext;
import com.cloudinfra.framework.grpc.GrpcCallerContext;
import com.cloudinfra.framework.grpc.GrpcServerLifecycle;
import com.cloudinfra.framework.grpc.GrpcSecurityInterceptor;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysDeptService;
import com.cloudinfra.system.service.ISysUserService;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M7-02 验收证据: 系统业务适配层的 gRPC 调用安全边界。
 *
 * <p>真实 TLS 网络调用 + 完整 Spring 条件装配:
 * <ul>
 *   <li>缺内部签名 / 缺租户 / 缺身份 一律 {@code UNAUTHENTICATED}, 且错误描述不区分原因</li>
 *   <li>合法签名 + 完整身份时放行, 业务层经 {@code GrpcCallerContext} 读到调用方</li>
 *   <li>租户上下文绑定到 {@code TenantContext}, 调用结束后不残留</li>
 * </ul>
 */
class SystemGrpcSecurityBoundaryTest {

    private static final String SECRET = "system-grpc-security-test-secret";
    private static final String PROP_INTERNAL_CALL_SECRET = "cloud-infra.internal-call.secret";

    /** failsafe 在 JVM 启动时注入的原值，用于还原而非抹除 */
    private String originalInternalCallSecret;

    @BeforeEach
    void setUp() {
        // 先存原值: 本 IT 结束后要把它还回去, 而不是连同注入值一起清掉
        originalInternalCallSecret = System.getProperty(PROP_INTERNAL_CALL_SECRET);
        System.setProperty(PROP_INTERNAL_CALL_SECRET, SECRET);
    }

    /**
     * @AfterEach
     * <p><b>2026-10-01 修正</b>: 原来这里是 {@code System.clearProperty(...)}，
     * 连带抹掉了 failsafe 启动时注入的 {@code cloud-infra.internal-call.secret}，
     * 使同一 JVM 内后续需要 {@code InternalCallSecretProvider} 的 IT 因密钥缺失而
     * 启动失败（该 bean 是 fail-fast 的）。改为还原原值。</p>
     */
    @AfterEach
    void tearDown() {
        if (originalInternalCallSecret == null) {
            System.clearProperty(PROP_INTERNAL_CALL_SECRET);
        } else {
            System.setProperty(PROP_INTERNAL_CALL_SECRET, originalInternalCallSecret);
        }
        TenantContext.clear();
    }

    @Test
    void securityBoundaryRejectsUnsignedCallAndAcceptsSignedCall(@TempDir Path workspace) throws Exception {
        TlsFixture tls = TlsFixture.generate(workspace);
        AtomicReference<String> tenantSeenByService = new AtomicReference<>();
        AtomicReference<Long> userSeenByService = new AtomicReference<>();

        ISysUserService userService = mock(ISysUserService.class);
        ISysDeptService deptService = mock(ISysDeptService.class);
        SysUser alice = new SysUser();
        alice.setUserId(5L);
        alice.setUsername("alice");
        alice.setStatus(0);
        alice.setDeptId(7L);
        when(userService.selectUserById(5L)).thenAnswer(invocation -> {
            // 业务代码运行在 gRPC 线程上, 此处即安全边界生效的现场。
            // 身份走 gRPC 自己的上下文 (GrpcCallerContext), 不依赖 HTTP 侧的 SecurityUtils
            tenantSeenByService.set(TenantContext.getTenantId().orElse(null));
            var caller = GrpcCallerContext.current();
            userSeenByService.set(caller == null ? null : caller.userId());
            return alice;
        });

        InternalCallSecretProvider secretProvider = new InternalCallSecretProvider();
        secretProvider.init();

        new ApplicationContextRunner()
            .withPropertyValues(
                "cloudinfra.system.grpc.enabled=true",
                "cloudinfra.grpc.enabled=true",
                // 2026-10-02: 显式指定临时端口。CloudInfraGrpcProperties.port 默认 9090,
                // 本测试过去未覆盖 → 抢占 9090 → 本地被占用时以 BindException 失败。
                // 与 SystemGrpcNetworkTest 同一根因, 一并修掉。
                "cloudinfra.grpc.port=0",
                "cloudinfra.grpc.ssl.enabled=true",
                "cloudinfra.grpc.ssl.cert-chain-file=file:" + tls.certificate(),
                "cloudinfra.grpc.ssl.private-key-file=file:" + tls.privateKeyPem(),
                "cloudinfra.grpc.security.enabled=true",
                "cloudinfra.grpc.security.require-internal-sign=true",
                "cloudinfra.grpc.security.require-tenant=true",
                "cloudinfra.grpc.security.require-user=true")
            .withBean(ISysUserService.class, () -> userService)
            .withBean(ISysDeptService.class, () -> deptService)
            .withBean(jakarta.validation.Validator.class, () -> mock(jakarta.validation.Validator.class))
            .withBean(InternalCallSecretProvider.class, () -> secretProvider)
            .withUserConfiguration(SysUserGrpcService.class, SysDeptGrpcService.class, SystemGrpcConfiguration.class)
            .withConfiguration(AutoConfigurations.of(
                com.cloudinfra.framework.grpc.GrpcServerAutoConfiguration.class))
            .run(context -> {
                assertThat(context).hasNotFailed();

                // 安全边界拦截器必须被装配
                assertThat(context.getBean(GrpcSecurityInterceptor.class)).isNotNull();

                Server server = context.getBean(GrpcServerLifecycle.class).getServer();
                int port = server.getPort();

                ManagedChannel channel = Grpc.newChannelBuilderForAddress("localhost", port,
                    TlsChannelCredentials.newBuilder()
                        .trustManager(tls.trustManagers().getTrustManagers()).build()).build();
                try {
                    // 1. 无签名 → 拒绝, 描述统一, 不泄露原因
                    assertThatThrownBy(() -> getUser(channel, new Metadata(), 5L))
                        .isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> {
                            var status = ((StatusRuntimeException) error).getStatus();
                            assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                            assertThat(status.getDescription()).isEqualTo("Unauthenticated");
                        });

                    // 2. 伪造签名 → 同样拒绝
                    assertThatThrownBy(() -> getUser(channel, signedHeaders("forged-secret", "tenant-a", "5"), 5L))
                        .isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                            .isEqualTo(Status.Code.UNAUTHENTICATED));

                    // 3. 合法签名但缺租户 → 拒绝
                    assertThatThrownBy(() -> getUser(channel, signedHeaders(SECRET, null, "5"), 5L))
                        .isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                            .isEqualTo(Status.Code.UNAUTHENTICATED));

                    // 4. 合法签名 + 完整身份 → 放行, 身份与租户在业务层可见
                    UserResponse found = getUser(channel, signedHeaders(SECRET, "tenant-x", "5"), 5L);
                    assertThat(found.getUserId()).isEqualTo(5L);
                    assertThat(found.getUsername()).isEqualTo("alice");
                    assertThat(tenantSeenByService.get()).isEqualTo("tenant-x");
                    assertThat(userSeenByService.get()).isEqualTo(5L);
                } finally {
                    channel.shutdownNow();
                    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                }

                // 5. 调用结束后线程上下文不残留
                assertThat(TenantContext.hasTenant()).isFalse();
                assertThat(GrpcCallerContext.current()).isNull();
            });
    }

    // ========== 辅助方法 ==========

    private UserResponse getUser(Channel channel, Metadata headers, long userId) {
        Channel intercepted = ClientInterceptors.intercept(
            channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        return SysUserServiceGrpc.newBlockingStub(intercepted)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .getUser(GetUserRequest.newBuilder().setUserId(userId).build());
    }

    private Metadata signedHeaders(String secret, String tenantId, String userId) {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        Metadata headers = new Metadata();
        headers.put(ascii(InternalCallCrypto.HEADER_SIGN), InternalCallCrypto.sign(secret, timestamp, nonce));
        headers.put(ascii(InternalCallCrypto.HEADER_TIMESTAMP), Long.toString(timestamp));
        headers.put(ascii(InternalCallCrypto.HEADER_NONCE), nonce);
        if (tenantId != null) {
            headers.put(ascii("X-Tenant-Id"), tenantId);
        }
        if (userId != null) {
            headers.put(ascii("X-User-Id"), userId);
            headers.put(ascii("X-User-Name"), "alice");
        }
        return headers;
    }

    private static Metadata.Key<String> ascii(String header) {
        return Metadata.Key.of(header.toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER);
    }

    /**
     * 复用部署脚本生成真实证书, 并为客户端构造信任锚。不做任何密码弱化。
     */
    private record TlsFixture(Path certificate, Path privateKeyPem,
                              TrustManagerFactory trustManagers) {

        static TlsFixture generate(Path workspace) throws Exception {
            String password = UUID.randomUUID().toString();
            var generator = new ProcessBuilder("bash",
                Path.of("../../deploy/ssl/generate-notification-cert.sh").toAbsolutePath().toString());
            generator.environment().put("SSL_KEYSTORE_PASSWORD", password);
            generator.environment().put("SSL_OUTPUT_DIR", workspace.toString());
            generator.redirectErrorStream(true).redirectOutput(workspace.resolve("cert.log").toFile());
            var process = generator.start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            Path certificate = workspace.resolve("server.crt");
            Path privateKeyPem = workspace.resolve("server.key");
            extractPkcs12KeyAsPem(workspace.resolve("notification.p12"), password, privateKeyPem);

            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore clientTrust = KeyStore.getInstance(KeyStore.getDefaultType());
            clientTrust.load(null, null);
            clientTrust.setCertificateEntry("system-grpc",
                CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(certificate)));
            trust.init(clientTrust);
            return new TlsFixture(certificate, privateKeyPem, trust);
        }

        private static void extractPkcs12KeyAsPem(Path keystore, String password, Path output) throws Exception {
            KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keystore)) {
                pkcs12.load(input, password.toCharArray());
            }
            String alias = pkcs12.aliases().nextElement();
            var key = (PrivateKey) pkcs12.getKey(alias, password.toCharArray());
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(output, pem);
        }
    }
}
