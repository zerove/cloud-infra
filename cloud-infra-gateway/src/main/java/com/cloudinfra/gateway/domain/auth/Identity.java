package com.cloudinfra.gateway.domain.auth;

/**
 * 身份信息记录
 *
 * @param userId    用户 ID
 * @param username  用户名
 * @param tenantId  所属租户（M6-01）。来自 JWT 中由 auth 签发的 {@code tenantId} claim,
 *                  而非客户端请求头 —— 头是客户端可控的, 用它等于让调用方自选租户。
 *                  白名单请求没有登录主体, 此处为 {@code null}。
 */
public record Identity(Long userId, String username, String tenantId) {

    public Identity(Long userId, String username) {
        this(userId, username, null);
    }
}
