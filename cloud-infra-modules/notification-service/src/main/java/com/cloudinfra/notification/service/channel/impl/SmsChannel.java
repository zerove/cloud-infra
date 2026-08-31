package com.cloudinfra.notification.service.channel.impl;

import com.cloudinfra.notification.service.channel.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsChannel implements NotificationChannel {

    @Override
    public String getChannelCode() {
        return "sms";
    }

    @Override
    public int getPriority() {
        return 10; // 最高优先级
    }

    @Override
    public boolean supportsHtml() {
        return false;
    }

    @Override
    public void send(Long userId, String title, String content) {
        String finalSms = String.format("[%s] %s", title, content);
        log.info("[SmsChannel] 发送短信 -> 用户: {}, 内容: {}", 
            userId, finalSms);
    }
}