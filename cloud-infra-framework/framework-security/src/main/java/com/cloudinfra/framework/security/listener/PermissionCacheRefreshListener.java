package com.cloudinfra.framework.security.listener;

import com.cloudinfra.common.core.security.IPermissionProvider;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Component;

/**
 * 权限缓存刷新监听器
 *
 * 监听 RBAC 变更事件（Redis Pub/Sub 频道 auth:refresh:channel），
 * 收到消息后失效对应用户的权限缓存，解决权限变更最长 30 分钟才生效的问题。
 *
 * 消息格式：
 *   user:{userId}  -> 清除指定用户缓存
 *   user:*         -> 清除全部用户缓存（全量刷新）
 */
@Component
public class PermissionCacheRefreshListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PermissionCacheRefreshListener.class);
    private static final String CHANNEL = "auth:refresh:channel";
    private static final String USER_PREFIX = "user:";

    private final IPermissionProvider permissionProvider;
    private final CacheManager cacheManager;

    public PermissionCacheRefreshListener(
        IPermissionProvider permissionProvider,
        CacheManager cacheManager,
        RedisMessageListenerContainer container
    ) {
        this.permissionProvider = permissionProvider;
        this.cacheManager = cacheManager;
        // 注册订阅（构造时完成，避免与容器初始化顺序冲突）
        container.addMessageListener(new MessageListenerAdapter(this), new ChannelTopic(CHANNEL));
        log.info("权限缓存刷新监听器已注册，频道: {}", CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            log.debug("收到权限刷新消息: {}", body);
            if (body == null || body.isBlank()) {
                return;
            }
            if (body.startsWith(USER_PREFIX)) {
                String userIdStr = body.substring(USER_PREFIX.length());
                if ("*".equals(userIdStr)) {
                    clearAll();
                    return;
                }
                try {
                    Long userId = Long.valueOf(userIdStr);
                    permissionProvider.clearPermissionCache(userId);
                    log.info("权限缓存已刷新, userId={}", userId);
                } catch (NumberFormatException e) {
                    log.warn("忽略非法的权限刷新消息: {}", body);
                }
            } else {
                log.warn("忽略未知的权限刷新消息: {}", body);
            }
        } catch (Exception e) {
            log.error("处理权限刷新消息失败", e);
        }
    }

    private void clearAll() {
        Cache cache = cacheManager.getCache("permissions");
        if (cache != null) {
            cache.clear();
            log.info("权限缓存已全量清空");
        }
    }
}
