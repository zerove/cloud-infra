package com.cloudinfra.common.core.event;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件发布服务
 * 统一事件发布入口，支持同步/异步、重试、死信队列
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublisher {

    private final StreamBridge streamBridge;

    /**
     * 发布事件到默认输出绑定
     */
    public <T> void publish(String bindingName, T event) {
        try {
            boolean sent = streamBridge.send(bindingName, event);
            if (sent) {
                log.debug("事件发布成功: {} -> {}", event.getClass().getSimpleName(), bindingName);
            } else {
                log.warn("事件发布失败（通道未就绪）: {} -> {}", event.getClass().getSimpleName(), bindingName);
            }
        } catch (Exception e) {
            log.error("事件发布异常: {} -> {}", event.getClass().getSimpleName(), bindingName, e);
            throw new EventPublishException("Failed to publish event: " + event.getClass().getSimpleName(), e);
        }
    }

    /**
     * 异步发布事件
     */
    public <T> java.util.concurrent.CompletableFuture<Void> publishAsync(String bindingName, T event) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> publish(bindingName, event));
    }

    /**
     * 发布用户注册事件
     */
    public void publishUserRegistered(com.cloudinfra.common.core.event.UserRegisteredEvent event) {
        publish("user-events-out-0", event);
    }

    /**
     * 发布用户登录事件
     */
    public void publishUserLogin(com.cloudinfra.common.core.event.UserLoginEvent event) {
        publish("user-events-out-0", event);
    }

    /**
     * 发布数据变更事件
     */
    public void publishDataChange(com.cloudinfra.common.core.event.DataChangeEvent event) {
        publish("data-change-events-out-0", event);
    }

    /**
     * 事件发布异常
     */
    public static class EventPublishException extends RuntimeException {
        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}