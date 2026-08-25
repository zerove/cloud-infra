package com.cloudinfra.framework.security.listener;

import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.common.core.security.PermissionRefreshProtocol;
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
public class PermissionCacheRefreshListener implements MessageListener,
        org.springframework.context.SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PermissionCacheRefreshListener.class);


    private final IPermissionProvider permissionProvider;
    private final CacheManager cacheManager;

    private final RedisMessageListenerContainer container;

    private volatile boolean running = false;

    public PermissionCacheRefreshListener(
        IPermissionProvider permissionProvider,
        CacheManager cacheManager,
        RedisMessageListenerContainer container
    ) {
        this.permissionProvider = permissionProvider;
        this.cacheManager = cacheManager;
        this.container = container;
    }

    /**
     * 订阅注册移入生命周期启动阶段：所有单例 bean 就绪、容器可安全接受监听器
     * 后才挂订阅，消除构造期注册对初始化顺序的依赖。
     */
    @Override
    public void start() {
        container.addMessageListener(new MessageListenerAdapter(this),
            new ChannelTopic(PermissionRefreshProtocol.CHANNEL));
        running = true;
        log.info("权限缓存刷新监听器已注册，频道: {}", PermissionRefreshProtocol.CHANNEL);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            log.debug("收到权限刷新消息: {}", body);
            if (body == null || body.isBlank()) {
                return;
            }
            if (body.startsWith(PermissionRefreshProtocol.USER_PREFIX)) {
                String userIdStr = body.substring(PermissionRefreshProtocol.USER_PREFIX.length());
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
        Cache permissions = cacheManager.getCache("permissions");
        if (permissions != null) {
            permissions.clear();
            log.info("权限缓存已全量清空");
        }
        // userContexts 与 permissions 同源（RemotePermissionProvider 双缓存），必须一并清理
        Cache userContexts = cacheManager.getCache("userContexts");
        if (userContexts != null) {
            userContexts.clear();
            log.info("用户上下文缓存已全量清空");
        }
    }
}
