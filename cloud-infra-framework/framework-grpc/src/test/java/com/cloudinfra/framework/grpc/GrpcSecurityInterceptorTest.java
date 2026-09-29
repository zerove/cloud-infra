package com.cloudinfra.framework.grpc;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.tenant.TenantContext;
import com.cloudinfra.grpc.lib.EchoRequest;
import com.cloudinfra.grpc.lib.EchoResponse;
import com.cloudinfra.grpc.lib.EchoServiceGrpc;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-02 安全边界: 真实进程内 gRPC 调用链上的身份校验与上下文绑定。
 *
 * <p>覆盖未认证拒绝、伪造签名拒绝、租户/身份缺失拒绝、合法调用放行,
 * 以及线程上下文在调用期间可见、调用后不残留。
 */
class GrpcSecurityInterceptorTest {

    private static final String SECRET = "grpc-security-test-secret";

    private final Deque<ManagedChannel> channels = new ArrayDeque<>();
    private final Deque<Server> servers = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
        channels.clear();
        servers.clear();
        TenantContext.clear();
    }

    // ========== 拒绝路径 ==========

    @Test
    void rejectsCallWithoutInternalSignature() throws Exception {
        assertRejected(securityConfig(true, true, true), new Metadata(),
            "未携带任何签名头");
    }

    @Test
    void rejectsCallWithForgedSignature() throws Exception {
        Metadata headers = signedHeaders("wrong-secret");
        assertRejected(securityConfig(true, true, true), headers, "签名密钥不匹配");
    }

    @Test
    void rejectsCallWithReplayedTimestampOutsideWindow() throws Exception {
        Metadata headers = signedHeaders(SECRET, System.currentTimeMillis() - 6 * 60 * 1000L);
        assertRejected(securityConfig(true, true, true), headers, "签名超出 5 分钟时间窗");
    }

    @Test
    void rejectsCallWithMalformedTimestamp() throws Exception {
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "tenant-a");
        headers.put(GrpcSecurityInterceptor.METADATA_USER_ID, "7");
        headers.put(key(InternalCallCrypto.HEADER_TIMESTAMP), "not-a-number");
        assertRejected(securityConfig(true, true, true), headers, "时间戳非数字");
    }

    @Test
    void rejectsCallWithoutTenantContext() throws Exception {
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_USER_ID, "7");
        assertRejected(securityConfig(true, true, true), headers, "缺少租户上下文");
    }

    @Test
    void rejectsCallWithoutCallerIdentity() throws Exception {
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "tenant-a");
        assertRejected(securityConfig(true, true, true), headers, "缺少调用方身份");
    }

    @Test
    void rejectsCallWithNonPositiveUserId() throws Exception {
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "tenant-a");
        headers.put(GrpcSecurityInterceptor.METADATA_USER_ID, "0");
        assertRejected(securityConfig(true, true, true), headers, "用户 ID 非法");
    }

    @Test
    void errorDescriptionNeverLeaksWhyAuthenticationFailed() throws Exception {
        Metadata headers = signedHeaders("wrong-secret");
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "tenant-a");
        headers.put(GrpcSecurityInterceptor.METADATA_USER_ID, "7");
        ServerAndService started = startServer(securityConfig(true, true, true), new CapturingEchoService());

        assertThatThrownBy(() -> echo(started, headers))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(error -> {
                var status = ((StatusRuntimeException) error).getStatus();
                assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                assertThat(status.getDescription()).isEqualTo("Unauthenticated");
            });
    }

    // ========== 放行路径 ==========

    @Test
    void acceptsSignedCallAndBindsCallerAndTenantDuringInvocation() throws Exception {
        CapturingEchoService service = new CapturingEchoService();
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "tenant-x");
        headers.put(GrpcSecurityInterceptor.METADATA_USER_ID, "42");
        headers.put(GrpcSecurityInterceptor.METADATA_USER_NAME, "alice");

        EchoResponse response = echo(startServer(securityConfig(true, true, true), service), headers);

        assertThat(response.getMessage()).isEqualTo("ping");
        assertThat(service.seenCaller).isNotNull();
        assertThat(service.seenCaller.userId()).isEqualTo(42L);
        assertThat(service.seenCaller.username()).isEqualTo("alice");
        assertThat(service.seenCaller.tenantId()).isEqualTo("tenant-x");
        assertThat(service.seenCaller.fullMethodName())
            .isEqualTo("cloudinfra.grpc.EchoService/Echo");
        assertThat(service.seenTenant).isEqualTo("tenant-x");
    }

    @Test
    void threadContextDoesNotLeakAfterCallCompletes() throws Exception {
        CapturingEchoService service = new CapturingEchoService();
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "tenant-x");
        headers.put(GrpcSecurityInterceptor.METADATA_USER_ID, "42");

        echo(startServer(securityConfig(true, true, true), service), headers);

        assertThat(service.seenTenant).isEqualTo("tenant-x");
        assertThat(TenantContext.hasTenant()).isFalse();
        assertThat(GrpcCallerContext.current()).isNull();
    }

    @Test
    void allowsServiceOnlyCallWhenIdentityIsNotRequired() throws Exception {
        CapturingEchoService service = new CapturingEchoService();
        Metadata headers = signedHeaders(SECRET);
        headers.put(GrpcSecurityInterceptor.METADATA_TENANT_ID, "default");

        echo(startServer(securityConfig(true, true, false), service), headers);

        assertThat(service.seenCaller).isNotNull();
        assertThat(service.seenCaller.isServiceCall()).isTrue();
        assertThat(service.seenTenant).isEqualTo("default");
    }

    @Test
    void skipsAllValidationWhenSecurityBoundaryDisabled() throws Exception {
        CapturingEchoService service = new CapturingEchoService();
        EchoResponse response = echo(startServer(securityConfig(false, true, true), service),
            new Metadata());

        assertThat(response.getMessage()).isEqualTo("ping");
        assertThat(service.seenCaller).isNull();
        assertThat(service.seenTenant).isNull();
    }

    // ========== 辅助方法 ==========

    private record ServerAndService(ManagedChannel channel) {
    }

    private CloudInfraGrpcProperties.Security securityConfig(
            boolean enabled, boolean requireTenant, boolean requireUser) {
        var security = new CloudInfraGrpcProperties.Security();
        security.setEnabled(enabled);
        security.setRequireInternalSign(true);
        security.setRequireTenant(requireTenant);
        security.setRequireUser(requireUser);
        return security;
    }

    private void assertRejected(CloudInfraGrpcProperties.Security security,
                                Metadata headers, String scenario) throws Exception {
        ServerAndService started = startServer(security, new CapturingEchoService());
        assertThatThrownBy(() -> echo(started, headers))
            .as("场景: %s", scenario)
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    private ServerAndService startServer(CloudInfraGrpcProperties.Security security,
                                         CapturingEchoService service) throws Exception {
        String name = "security-test-" + UUID.randomUUID();
        Server server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .intercept(new GrpcSecurityInterceptor(security, SECRET))
            .addService(service)
            .build()
            .start();
        servers.push(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name)
            .directExecutor()
            .build();
        channels.push(channel);
        return new ServerAndService(channel);
    }

    private EchoResponse echo(ServerAndService started, Metadata headers) {
        Channel intercepted = ClientInterceptors.intercept(
            started.channel(), MetadataUtils.newAttachHeadersInterceptor(headers));
        return EchoServiceGrpc.newBlockingStub(intercepted)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .echo(EchoRequest.newBuilder().setMessage("ping").build());
    }

    private Metadata signedHeaders(String secret) {
        return signedHeaders(secret, System.currentTimeMillis());
    }

    private Metadata signedHeaders(String secret, long timestamp) {
        String nonce = UUID.randomUUID().toString();
        Metadata headers = new Metadata();
        headers.put(key(InternalCallCrypto.HEADER_SIGN), InternalCallCrypto.sign(secret, timestamp, nonce));
        headers.put(key(InternalCallCrypto.HEADER_TIMESTAMP), Long.toString(timestamp));
        headers.put(key(InternalCallCrypto.HEADER_NONCE), nonce);
        return headers;
    }

    private static Metadata.Key<String> key(String header) {
        return Metadata.Key.of(header.toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER);
    }

    /** 捕获业务执行期间可见的调用方身份与租户上下文。 */
    private static final class CapturingEchoService extends EchoServiceGrpc.EchoServiceImplBase {
        private GrpcCaller seenCaller;
        private String seenTenant;

        @Override
        public void echo(EchoRequest request, StreamObserver<EchoResponse> observer) {
            seenCaller = GrpcCallerContext.current();
            seenTenant = TenantContext.getTenantId().orElse(null);
            observer.onNext(EchoResponse.newBuilder().setMessage(request.getMessage()).build());
            observer.onCompleted();
        }
    }
}
