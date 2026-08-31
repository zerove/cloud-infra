package com.cloudinfra.notification.service.channel.impl;

import com.cloudinfra.notification.service.channel.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WebSocketChannel implements NotificationChannel {

    @Override
    public String getChannelCode() {
        return "websocket";
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public boolean supportsHtml() {
        return true;
    }

    @Override
    public void send(Long userId, String title, String content) {
        log.info("[WebSocketChannel] 推送站内信 -> 用户: {}, 标题: {}", 
            userId, title);
    }
}