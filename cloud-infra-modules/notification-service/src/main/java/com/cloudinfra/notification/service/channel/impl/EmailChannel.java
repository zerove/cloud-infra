package com.cloudinfra.notification.service.channel.impl;

import com.cloudinfra.notification.service.channel.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    @Override
    public String getChannelCode() {
        return "email";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public boolean supportsHtml() {
        return true;
    }

    @Override
    public void send(Long userId, String title, String content) {
        log.info("[EmailChannel] 发送邮件 -> 用户: {}, 标题: {}, 内容长度: {}", 
            userId, title, content.length());
    }
}