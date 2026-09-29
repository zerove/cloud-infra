package com.cloudinfra.common.core.security;

/**
 * 调用方身份向线程上下文绑定的扩展点 (M7-02 安全边界).
 *
 * <p>协议入口 (HTTP 过滤链 / gRPC 拦截器) 负责校验身份,
 * 但 Spring Security 的 {@code SecurityContextHolder} 属于线程本地变量,
 * 且不应让协议框架在编译期强依赖 spring-security, 因此由本 SPI 桥接。
 *
 * <p>实现约定: {@link #bind} 与 {@link #clear} 必定成对出现在同一线程的一次调用内,
 * 因此实现可以安全地使用 {@code ThreadLocal}。
 */
public interface CallerContextBinder {

    /** 绑定调用方身份到当前线程上下文。 */
    void bind(PlatformCaller caller);

    /** 清理当前线程上下文。实现需容忍未绑定状态。 */
    void clear();

    /**
     * 缺省实现: 不做任何绑定, 仅保证协议原生上下文 (如 gRPC {@code GrpcCallerContext}) 生效。
     *
     * <p>当 classpath 上没有 spring-security 时使用此实现。
     */
    final class Noop implements CallerContextBinder {
        @Override
        public void bind(PlatformCaller caller) {
            // 无线程上下文可绑定
        }

        @Override
        public void clear() {
            // 无线程上下文可清理
        }
    }
}
