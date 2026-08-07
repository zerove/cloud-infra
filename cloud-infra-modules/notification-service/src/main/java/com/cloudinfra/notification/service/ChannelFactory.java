package com.cloudinfra.notification.service;

import com.cloudinfra.notification.service.channel.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知渠道工厂 - 实现渠道的动态路由
 *
 * <p>注意：bean 名必须为 {@code notificationChannelFactory}。Spring Cloud Stream 的
 * {@code BinderFactoryAutoConfiguration} 会注册同名 bean（{@code channelFactory}），
 * 在 {@code allow-bean-definition-overriding=true} 时若同名会相互覆盖导致本类丢失。</p>
 */
@Component("notificationChannelFactory")
public class ChannelFactory {

    private final Map<String, NotificationChannel> channels = new ConcurrentHashMap<>();

    public ChannelFactory(java.util.List<NotificationChannel> channelList) {
        channelList.forEach(channel -> channels.put(channel.getChannelCode(), channel));
    }

    /**
     * 根据渠道编码获取对应的通道实现
     */
    public Optional<NotificationChannel> getChannel(String code) {
        return Optional.ofNullable(channels.get(code));
    }

    /**
     * 获取所有可用渠道
     */
    public Map<String, NotificationChannel> getAllChannels() {
        return channels;
    }
}
