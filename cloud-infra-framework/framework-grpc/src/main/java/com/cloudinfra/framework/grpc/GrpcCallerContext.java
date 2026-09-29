package com.cloudinfra.framework.grpc;

import io.grpc.Context;
import org.jspecify.annotations.Nullable;

/**
 * gRPC 调用方身份读取入口 (M7-02 安全边界).
 *
 * <p>安全拦截器把 {@link GrpcCaller} 放入 gRPC 原生 {@link Context}, 随 RPC 调用自动传播,
 * 调用结束后自动回收, 因此业务适配层可在任意深度安全读取当前调用者。
 *
 * <pre>{@code
 * GrpcCaller caller = GrpcCallerContext.current();
 * Long userId = caller == null ? null : caller.userId();
 * }</pre>
 *
 * <p>未启用 {@code cloudinfra.grpc.security.enabled} 时恒返回 {@code null}。
 *
 * @since M7-02 Phase D
 */
public final class GrpcCallerContext {

    private static final Context.Key<GrpcCaller> CALLER_KEY =
        Context.key("cloud-infra-grpc-caller");

    private GrpcCallerContext() {
    }

    static Context.Key<GrpcCaller> key() {
        return CALLER_KEY;
    }

    /**
     * 获取当前调用的调用方身份; 未启用安全边界或无身份时返回 {@code null}。
     */
    @Nullable
    public static GrpcCaller current() {
        return CALLER_KEY.get();
    }

    /**
     * 获取当前调用的调用方身份; 缺失时抛出 {@link IllegalStateException}。
     */
    public static GrpcCaller require() {
        GrpcCaller caller = current();
        if (caller == null) {
            throw new IllegalStateException("No gRPC caller bound to current call");
        }
        return caller;
    }
}
