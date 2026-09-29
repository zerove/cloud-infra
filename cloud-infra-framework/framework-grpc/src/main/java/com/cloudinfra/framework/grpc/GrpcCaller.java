package com.cloudinfra.framework.grpc;

import org.jspecify.annotations.Nullable;

/**
 * gRPC 调用方身份 (M7-02 安全边界).
 *
 * <p>由 {@link GrpcSecurityInterceptor} 从请求元数据中提取并校验, 通过 {@link GrpcCallerContext}
 * 传递给业务适配层。业务代码不应信任请求体中的任何身份字段, 一律以本记录为准。
 *
 * <p><b>注意</b>: gRPC 通道内不使用 Spring Security 的 {@code SecurityContextHolder}。
 * 独立网络端口不经过网关过滤链, 用 HTTP 侧的身份机制反推 gRPC 身份会掩盖真实来源。
 * gRPC 路径请统一通过 {@link GrpcCallerContext#current()} 读取身份。
 *
 * @param userId        调用方用户 ID, 纯服务间调用时为 {@code null}
 * @param username      调用方用户名
 * @param tenantId      调用方所属租户, 租户隔离的权威来源
 * @param fullMethodName 被调用的完整方法名 (形如 {@code cloudinfra.system.SysUserService/GetUser})
 *
 * @since M7-02 Phase D
 */
public record GrpcCaller(
    @Nullable Long userId,
    @Nullable String username,
    @Nullable String tenantId,
    String fullMethodName
) {

    public GrpcCaller {
        fullMethodName = fullMethodName == null ? "" : fullMethodName;
    }

    /** 是否为服务间内部调用 (无终端用户身份). */
    public boolean isServiceCall() {
        return userId == null;
    }

    /** 短方法名 (不含服务前缀), 便于权限映射与日志使用. */
    public String shortMethodName() {
        int slash = fullMethodName.lastIndexOf('/');
        return slash < 0 ? fullMethodName : fullMethodName.substring(slash + 1);
    }

    /** 服务全名 (不含方法名). */
    public String serviceName() {
        int slash = fullMethodName.lastIndexOf('/');
        return slash < 0 ? fullMethodName : fullMethodName.substring(0, slash);
    }
}
