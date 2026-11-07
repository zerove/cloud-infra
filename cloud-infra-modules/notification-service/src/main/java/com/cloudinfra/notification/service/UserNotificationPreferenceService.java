package com.cloudinfra.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.notification.domain.UserNotificationPreference;
import com.cloudinfra.notification.domain.dto.NotificationPreferenceRequest;
import com.cloudinfra.notification.mapper.UserNotificationPreferenceMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户通知偏好（当前用户作用域）。数据表由 {@code @TenantFilter} 约束租户隔离。
 */
@Service
@RequiredArgsConstructor
public class UserNotificationPreferenceService {

    private final UserNotificationPreferenceMapper preferenceMapper;

    public List<UserNotificationPreference> listByUserId(Long userId) {
        return preferenceMapper.selectList(new LambdaQueryWrapper<UserNotificationPreference>()
            .eq(UserNotificationPreference::getUserId, userId)
            .orderByAsc(UserNotificationPreference::getNotificationType)
            .orderByAsc(UserNotificationPreference::getChannelCode));
    }

    /**
     * 按 (userId, notificationType, channelCode) 幂等 upsert，仅作用于当前用户。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<UserNotificationPreference> saveForUser(Long userId, List<NotificationPreferenceRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return listByUserId(userId);
        }
        for (NotificationPreferenceRequest req : requests) {
            String type = req.notificationType().trim();
            String channel = req.channelCode().trim();
            LambdaQueryWrapper<UserNotificationPreference> qw = new LambdaQueryWrapper<UserNotificationPreference>()
                .eq(UserNotificationPreference::getUserId, userId)
                .eq(UserNotificationPreference::getNotificationType, type)
                .eq(UserNotificationPreference::getChannelCode, channel);
            UserNotificationPreference existing = preferenceMapper.selectOne(qw);
            if (existing != null) {
                existing.setEnabled(req.enabled());
                preferenceMapper.updateById(existing);
            } else {
                UserNotificationPreference created = new UserNotificationPreference();
                created.setUserId(userId);
                created.setNotificationType(type);
                created.setChannelCode(channel);
                created.setEnabled(req.enabled());
                preferenceMapper.insert(created);
            }
        }
        return listByUserId(userId);
    }
}
