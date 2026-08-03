package com.cloudinfra.notification;

import com.cloudinfra.notification.service.ChannelFactory;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import com.cloudinfra.notification.service.channel.impl.EmailChannel;
import com.cloudinfra.notification.service.channel.impl.SmsChannel;
import com.cloudinfra.notification.service.channel.impl.WebSocketChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 通知服务测试配置
 * 手动装配渠道实现与 ChannelFactory，避免组件扫描遗漏
 */
@Configuration
public class NotificationTestConfig {

    @Bean
    @Primary
    public EmailChannel emailChannel() {
        return new EmailChannel();
    }

    @Bean
    @Primary
    public SmsChannel smsChannel() {
        return new SmsChannel();
    }

    @Bean
    @Primary
    public WebSocketChannel webSocketChannel() {
        return new WebSocketChannel();
    }

    @Bean
    @Primary
    public ChannelFactory channelFactory() {
        return new ChannelFactory(List.of(
            new EmailChannel(),
            new SmsChannel(),
            new WebSocketChannel()
        ));
    }
}