package com.cloudinfra.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.notification.domain.UserNotificationPreference;
import com.cloudinfra.notification.domain.dto.NotificationPreferenceRequest;
import com.cloudinfra.notification.mapper.UserNotificationPreferenceMapper;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
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

    private static final int MAX_PREFERENCE_ITEMS = 100;

    private final UserNotificationPreferenceMapper preferenceMapper;

    private static void requireUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录或身份无效");
        }
    }

    public List<UserNotificationPreference> listByUserId(Long userId) {
        requireUserId(userId);
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
        requireUserId(userId);
        if (requests == null || requests.isEmpty()) {
            return listByUserId(userId);
        }
        if (requests.size() > MAX_PREFERENCE_ITEMS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "单次保存偏好项不能超过 " + MAX_PREFERENCE_ITEMS + " 条");
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
