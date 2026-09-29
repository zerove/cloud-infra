package com.cloudinfra.framework.grpc;

import com.cloudinfra.common.core.security.PlatformCaller;
import io.grpc.Context;
import org.jspecify.annotations.Nullable;

/**
 * gRPC 调用方身份读取入口 (M7-02 安全边界).
 *
 * <p>安全拦截器把 {@link PlatformCaller} 放入 gRPC 原生 {@link Context}, 随 RPC 调用自动传播,
 * 调用结束后自动回收, 因此业务适配层可在任意深度安全读取当前调用者。
 *
 * <pre>{@code
 * PlatformCaller caller = GrpcCallerContext.current();
 * if (caller.isServiceCall()) { ... }
 * }</pre>
 *
 * <p>未启用 {@code cloudinfra.grpc.security.enabled} 时恒返回 {@code null}。
 */
public final class GrpcCallerContext {

    private static final Context.Key<PlatformCaller> CALLER_KEY =
        Context.key("cloud-infra-grpc-caller");

    private GrpcCallerContext() {
    }

    static Context.Key<PlatformCaller> key() {
        return CALLER_KEY;
    }

    /**
     * 获取当前调用的调用方身份; 未启用安全边界或无身份时返回 {@code null}。
     */
    @Nullable
    public static PlatformCaller current() {
        return CALLER_KEY.get();
    }

    /**
     * 获取当前调用的调用方身份; 缺失时抛出 {@link IllegalStateException}。
     */
    public static PlatformCaller require() {
        PlatformCaller caller = current();
        if (caller == null) {
            throw new IllegalStateException("No gRPC caller bound to current call");
        }
        return caller;
    }
}
