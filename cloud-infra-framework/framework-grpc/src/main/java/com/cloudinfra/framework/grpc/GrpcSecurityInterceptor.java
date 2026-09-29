package com.cloudinfra.framework.grpc;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.security.CallerContextBinder;
import com.cloudinfra.common.core.security.PlatformCaller;
import com.cloudinfra.common.core.tenant.TenantContext;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * gRPC 调用身份与租户安全边界拦截器 (M7-02 安全边界).
 *
 * <p>与 HTTP 侧 {@code HeaderAuthenticationFilter} 对齐, 为 gRPC 入口补齐三道边界:
 * <ol>
 *   <li><b>内部调用签名</b>: 校验 {@code X-Internal-Call} HMAC 签名, 防止绕过网关直连伪造身份</li>
 *   <li><b>调用方身份</b>: 提取 {@code X-User-Id} / {@code X-User-Name} 供业务适配层做数据范围判定</li>
 *   <li><b>租户上下文</b>: 提取 {@code X-Tenant-Id} 并绑定到 {@link TenantContext}, 使 MyBatis 租户拦截器生效</li>
 * </ol>
 *
 * <p>任一校验失败即以 {@link Status#UNAUTHENTICATED} 关闭调用, 且错误信息不泄露具体原因,
 * 避免攻击者据此区分"缺签名"与"签名错误"。
 *
 * <p>安全边界默认关闭 ({@code cloudinfra.grpc.security.enabled=false}), 业务模块需显式开启。
 *
 * @author Cloud-Infra Team
 * @since M7-02 Phase D (安全边界)
 */
@Slf4j
public class GrpcSecurityInterceptor implements ServerInterceptor {

    /** 调用方用户 ID 元数据键 (HTTP 头名的小写形式). */
    public static final Metadata.Key<String> METADATA_USER_ID =
        Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    /** 调用方用户名元数据键. */
    public static final Metadata.Key<String> METADATA_USER_NAME =
        Metadata.Key.of("x-user-name", Metadata.ASCII_STRING_MARSHALLER);
    /** 调用方租户元数据键. */
    public static final Metadata.Key<String> METADATA_TENANT_ID =
        Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> METADATA_INTERNAL_SIGN =
        Metadata.Key.of(InternalCallCrypto.HEADER_SIGN.toLowerCase(Locale.ROOT),
            Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> METADATA_INTERNAL_TIMESTAMP =
        Metadata.Key.of(InternalCallCrypto.HEADER_TIMESTAMP.toLowerCase(Locale.ROOT),
            Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> METADATA_INTERNAL_NONCE =
        Metadata.Key.of(InternalCallCrypto.HEADER_NONCE.toLowerCase(Locale.ROOT),
            Metadata.ASCII_STRING_MARSHALLER);

    private final CloudInfraGrpcProperties.Security security;
    private final String internalCallSecret;
    private final CallerContextBinder binder;

    public GrpcSecurityInterceptor(CloudInfraGrpcProperties.Security security,
                                   @Nullable String internalCallSecret,
                                   CallerContextBinder binder) {
        this.security = security;
        this.internalCallSecret = internalCallSecret;
        this.binder = binder;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        if (!security.isEnabled()) {
            return next.startCall(call, headers);
        }

        String method = call.getMethodDescriptor().getFullMethodName();

        if (security.isRequireInternalSign() && !isTrustedInternalCall(headers)) {
            return reject(call, "Internal call signature is missing or invalid");
        }

        String tenantId = trimmed(headers.get(METADATA_TENANT_ID));
        if (security.isRequireTenant() && tenantId == null) {
            return reject(call, "Tenant context is required");
        }

        Long userId = parseUserId(trimmed(headers.get(METADATA_USER_ID)));
        if (security.isRequireUser() && userId == null) {
            return reject(call, "Caller identity is required");
        }

        String username = trimmed(headers.get(METADATA_USER_NAME));
        PlatformCaller caller = new PlatformCaller(userId, username, tenantId, "grpc:" + method);

        Context context = Context.current().withValue(GrpcCallerContext.key(), caller);
        ServerCall.Listener<ReqT> delegate =
            Contexts.interceptCall(context, call, headers, next);

        log.debug("gRPC 安全边界已绑定调用方: method={}, userId={}, tenantId={}, serviceCall={}",
            method, userId, tenantId, caller.isServiceCall());

        return new CallerBindingListener<>(delegate, caller);
    }

    /** 校验内部调用 HMAC 签名, 与 HTTP 侧 {@code HeaderAuthenticationFilter} 同一套密钥与时间窗. */
    private boolean isTrustedInternalCall(Metadata headers) {
        String sign = trimmed(headers.get(METADATA_INTERNAL_SIGN));
        String timestamp = trimmed(headers.get(METADATA_INTERNAL_TIMESTAMP));
        String nonce = trimmed(headers.get(METADATA_INTERNAL_NONCE));
        if (sign == null || timestamp == null || nonce == null) {
            return false;
        }
        long epochMillis;
        try {
            epochMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException invalid) {
            return false;
        }
        return InternalCallCrypto.verify(internalCallSecret, sign, epochMillis, nonce);
    }

    @Nullable
    private static Long parseUserId(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    @Nullable
    private static String trimmed(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, String reason) {
        log.warn("gRPC 调用被安全边界拒绝: method={}, reason={}",
            call.getMethodDescriptor().getFullMethodName(), reason);
        call.close(Status.UNAUTHENTICATED.withDescription("Unauthenticated"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }

    /**
     * 在每个监听器回调前后成对绑定/清理线程上下文.
     *
     * <p>gRPC 的监听器回调不保证与 {@code interceptCall} 同一线程,
     * 因此必须在每个回调内独立绑定, 而不是在拦截器入口绑定一次。
     */
    private final class CallerBindingListener<ReqT>
            extends SimpleForwardingServerCallListener<ReqT> {

        private final PlatformCaller caller;

        private CallerBindingListener(ServerCall.Listener<ReqT> delegate, PlatformCaller caller) {
            super(delegate);
            this.caller = caller;
        }

        @Override
        public void onMessage(ReqT message) {
            withCaller(() -> super.onMessage(message));
        }

        @Override
        public void onHalfClose() {
            withCaller(super::onHalfClose);
        }

        @Override
        public void onReady() {
            withCaller(super::onReady);
        }

        @Override
        public void onComplete() {
            withCaller(super::onComplete);
        }

        @Override
        public void onCancel() {
            withCaller(super::onCancel);
        }

        /** 线程上下文仅在业务代码执行期间存在, 回调结束立即回收, 避免跨请求泄漏。 */
        private void withCaller(Runnable action) {
            binder.bind(caller);
            if (caller.tenantId() != null) {
                TenantContext.setTenantId(caller.tenantId());
            }
            try {
                action.run();
            } finally {
                binder.clear();
                TenantContext.clear();
            }
        }
    }
}
