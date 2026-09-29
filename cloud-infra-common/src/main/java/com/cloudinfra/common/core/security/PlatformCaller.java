package com.cloudinfra.common.core.security;

/**
 * 平台调用方身份 (M7-02 安全边界).
 *
 * <p>由各协议入口 (HTTP 头过滤链 / gRPC 元数据拦截器) 校验后写入线程上下文,
 * 业务层统一以此为权威身份来源, 不再信任请求体或 URL 中的任何身份字段。
 *
 * <p>放在 {@code cloud-infra-common} 是因为身份模型与传输协议无关:
 * 各协议框架只需实现 {@link CallerContextBinder} 即可复用同一套下游鉴权与数据范围逻辑。
 *
 * @param userId        调用方用户 ID, 纯服务间调用时为 {@code null}
 * @param username      调用方用户名
 * @param tenantId      调用方所属租户, 租户隔离的权威来源
 * @param entrypoint    入口描述 (如 {@code grpc:cloudinfra.system.SysUserService/GetUser}), 便于审计
 */
public record PlatformCaller(
    Long userId,
    String username,
    String tenantId,
    String entrypoint
) {

    public PlatformCaller {
        entrypoint = entrypoint == null ? "" : entrypoint;
    }

    /** 是否为服务间内部调用 (无终端用户身份). */
    public boolean isServiceCall() {
        return userId == null;
    }

    /** 短方法名 (不含服务前缀), 便于权限映射与日志使用. */
    public String shortMethodName() {
        int slash = entrypoint.lastIndexOf('/');
        return slash < 0 ? entrypoint : entrypoint.substring(slash + 1);
    }

    /** 服务全名 (不含方法名). */
    public String serviceName() {
        int slash = entrypoint.lastIndexOf('/');
        return slash < 0 ? entrypoint : entrypoint.substring(0, slash);
    }
}
