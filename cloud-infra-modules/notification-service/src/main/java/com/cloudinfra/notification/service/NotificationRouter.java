package com.cloudinfra.notification.service;

import com.cloudinfra.notification.domain.UserNotificationPreference;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.UserNotificationPreferenceMapper;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * 通知路由引擎 - 决定通知最终触达的渠道
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

    private final UserNotificationPreferenceMapper preferenceMapper;
    private final ChannelFactory channelFactory;

    /**
     * 根据请求和用户偏好计算最终发送渠道
     * @param request 发送请求
     * @return 经过路由过滤和优先级排序后的渠道列表
     */
    public List<NotificationChannel> route(NotificationSendRequest request) {
        // 1. 获取用户对该类型的通知偏好
        List<UserNotificationPreference> preferences = preferenceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserNotificationPreference>()
                .eq(UserNotificationPreference::getUserId, request.userId())
                .eq(UserNotificationPreference::getNotificationType, request.type().name())
        );

        // 提取所有被禁用的渠道
        Set<String> disabledChannels = preferences.stream()
            .filter(p -> Boolean.FALSE.equals(p.getEnabled()))
            .map(UserNotificationPreference::getChannelCode)
            .collect(Collectors.toSet());

        // 2. 确定初始候选渠道集
        Set<String> candidates = new HashSet<>();
        if (request.channels() != null && !request.channels().isEmpty()) {
            candidates.addAll(request.channels());
        } else {
            // 如果请求未指定，则应由模版决定 (这里简化为获取所有可用渠道)
            candidates.addAll(channelFactory.getAllChannels().keySet());
        }

        // 3. 过滤掉用户禁用的渠道
        candidates.removeAll(disabledChannels);

        // 4. 转换为 Channel 对象并根据优先级排序
        return candidates.stream()
            .map(code -> channelFactory.getChannel(code))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .sorted(Comparator.comparingInt(NotificationChannel::getPriority))
            .collect(Collectors.toList());
    }
}