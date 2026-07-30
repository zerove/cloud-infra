package com.cloudinfra.gateway.domain.auth;

/**
 * 会话存储接口 - 基础设施接缝
 */
public interface SessionStore {
    /**
     * 检查 Token ID (jti) 是否在黑名单中
     */
    boolean isBlacklisted(String jti);

    /**
     * 综合校验会话有效性（黑名单校验 + 会话存在校验）
     * @return true if session is valid and not blacklisted
     */
    boolean validateSession(Long userId, String jti);

    /**
     * 将 Token 加入黑名单
     */
    void markAsBlacklisted(String jti, long ttlSeconds);
}
