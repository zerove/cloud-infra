package com.cloudinfra.system.service.impl;

import com.cloudinfra.framework.redis.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户会话吊销器
 *
 * 用户被删除、禁用、重置密码后，必须使其存量会话立即失效，
 * 否则已签发的 JWT 在有效期内仍可继续访问（网关 validateSession
 * 依赖 auth:session:{userId}:{jti} 存在性判断，删除会话即拒绝）。
 *
 * 同时发布权限刷新事件，清理下游 permissions/userContexts 缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSessionRevoker {

    private static final String SESSION_PREFIX = "auth:session:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisService redisService;
    private final PermissionInvalidationService permissionInvalidationService;

    /**
     * 吊销指定用户的全部会话并刷新权限缓存。
     * 使用 SCAN 渐进遍历，避免 KEYS 阻塞 Redis。
     */
    public void revokeAllSessions(Long userId) {
        if (userId == null) {
            return;
        }
        String pattern = SESSION_PREFIX + userId + ":*";
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException
                | org.springframework.data.redis.RedisSystemException e) {
            // 会话扫描失败不阻断吊销主流程：键残留由 TTL 兜底过期
            log.warn("扫描用户会话失败, userId={}", userId, e);
        }
        for (String key : keys) {
            redisService.delete(key);
        }
        // 通知下游失效权限/用户上下文缓存（与 RBAC 变更同一通道）
        permissionInvalidationService.invalidateUser(userId);
        log.info("已吊销用户会话, userId={}, 会话数={}", userId, keys.size());
    }

    /**
     * 批量吊销多个用户的会话。
     */
    public void revokeAllSessions(List<Long> userIds) {
        if (userIds != null) {
            userIds.forEach(this::revokeAllSessions);
        }
    }
}
