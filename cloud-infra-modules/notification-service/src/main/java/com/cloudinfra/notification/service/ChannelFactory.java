package com.cloudinfra.notification.service;

import com.cloudinfra.notification.service.channel.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知渠道工厂 - 实现渠道的动态路由
 */
@Component
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
