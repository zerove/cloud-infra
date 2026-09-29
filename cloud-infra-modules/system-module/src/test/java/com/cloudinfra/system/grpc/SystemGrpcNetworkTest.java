package com.cloudinfra.system.grpc;

import com.cloudinfra.framework.grpc.GrpcServerLifecycle;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysDeptService;
import com.cloudinfra.system.service.ISysUserService;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M7-02 验收证据：真实 TLS 网络端到端。
 * 完整 Spring 条件装配（cloudinfra.system.grpc.enabled + cloudinfra.grpc.enabled）
 * → GrpcServerLifecycle 启动 Netty TLS 服务器 → grpc-netty 客户端经真实 TCP 调用适配器，
 * 断言真实往返响应与错误映射，容器关闭后确认服务器已停止。
 * 业务服务使用 mock，但传输、序列化、TLS、生命周期与条件装配全部真实。
 */
class SystemGrpcNetworkTest {

    @Test
    void adapterServesRealTlsSocketAndMapsErrorsOverTheWire(@TempDir Path workspace) throws Exception {
        // 证书：复用部署脚本（随机密码，PKCS12 + PEM 证书），不做任何密码弱化
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

        ISysUserService userService = mock(ISysUserService.class);
        ISysDeptService deptService = mock(ISysDeptService.class);
        SysUser alice = new SysUser();
        alice.setUserId(5L);
        alice.setUsername("alice");
        alice.setStatus(0);
        alice.setDeptId(7L);
        when(userService.selectUserById(5L)).thenReturn(alice);
        when(userService.selectUserById(6L)).thenReturn(null);
        when(userService.selectUserById(9L)).thenThrow(new IllegalStateException("db-password=secret"));

        Server[] serverRef = new Server[1];
        new ApplicationContextRunner()
                .withPropertyValues(
                    "cloudinfra.system.grpc.enabled=true",
                    "cloudinfra.grpc.enabled=true",
                    "cloudinfra.grpc.ssl.enabled=true",
                    "cloudinfra.grpc.ssl.cert-chain-file=file:" + certificate.toAbsolutePath(),
                    "cloudinfra.grpc.ssl.private-key-file=file:" + privateKeyPem.toAbsolutePath())
                .withBean(ISysUserService.class, () -> userService)
                .withBean(ISysDeptService.class, () -> deptService)
                .withBean(jakarta.validation.Validator.class, () -> mock(jakarta.validation.Validator.class))
                .withUserConfiguration(SysUserGrpcService.class, SysDeptGrpcService.class, SystemGrpcConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(GrpcServerLifecycle.class);
                    Server server = context.getBean(GrpcServerLifecycle.class).getServer();
                    serverRef[0] = server;
                    assertThat(server.isShutdown()).isFalse();
                    int port = server.getPort();
                    assertThat(port).isPositive();

                    var credentials = TlsChannelCredentials.newBuilder()
                            .trustManager(trust.getTrustManagers()).build();
                    ManagedChannel channel = Grpc.newChannelBuilderForAddress("localhost", port, credentials).build();
                    try {
                        var users = SysUserServiceGrpc.newBlockingStub(channel)
                                .withDeadlineAfter(5, TimeUnit.SECONDS);

                        UserResponse found = users.getUser(GetUserRequest.newBuilder().setUserId(5).build());
                        assertThat(found.getUserId()).isEqualTo(5L);
                        assertThat(found.getUsername()).isEqualTo("alice");
                        assertThat(found.getStatus()).isEqualTo("0");
                        assertThat(found.getDeptId()).isEqualTo(7L);

                        expectStatus(Status.Code.NOT_FOUND,
                                () -> users.getUser(GetUserRequest.newBuilder().setUserId(6).build()));

                        try {
                            users.getUser(GetUserRequest.newBuilder().setUserId(9).build());
                            fail("预期 INTERNAL");
                        } catch (StatusRuntimeException leak) {
                            assertThat(leak.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                            assertThat(leak.getMessage()).doesNotContain("db-password");
                        }

                        expectStatus(Status.Code.UNIMPLEMENTED,
                                () -> users.resetPassword(ResetPasswordRequest.getDefaultInstance()));
                        expectStatus(Status.Code.INVALID_ARGUMENT,
                                () -> users.listUsers(ListUsersRequest.newBuilder().setPageSize(5000).build()));
                        verify(userService, never()).selectUserList(org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any());
                    } finally {
                        channel.shutdownNow();
                        assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                    }
                });
        // 上下文已关闭：SmartLifecycle 停止回调必须停掉服务器
        assertThat(serverRef[0].isShutdown()).isTrue();
    }

    private interface RpcCall {
        void call() throws Exception;
    }

    private void expectStatus(Status.Code expected, RpcCall call) {
        try {
            call.call();
            fail("预期 " + expected);
        } catch (StatusRuntimeException error) {
            assertThat(error.getStatus().getCode()).isEqualTo(expected);
        } catch (Exception unexpected) {
            fail("预期 " + expected + "，实际异常: " + unexpected);
        }
    }

    private void extractPkcs12KeyAsPem(Path keystore, String password, Path output) throws Exception {
        KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) {
            pkcs12.load(input, password.toCharArray());
        }
        String alias = pkcs12.aliases().nextElement();
        var key = (java.security.PrivateKey) pkcs12.getKey(alias, password.toCharArray());
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(output, pem);
    }
}
