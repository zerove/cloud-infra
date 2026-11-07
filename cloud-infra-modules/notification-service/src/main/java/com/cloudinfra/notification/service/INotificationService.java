package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;

public interface INotificationService {
    /**
     * 发送通知 (支持立即发送或定时调度)
     */
    void sendNotification(NotificationSendRequest request);

    /**
     * 获取用户通知历史 (分页, 可按状态/类型过滤)。
     *
     * <p>分页窗口由 {@link PageQuery} 的 {@code normalized*} 统一归一化;
     * 返回 {@link PageResult} 携带 total。</p>
     */
    PageResult<Notification> getNotificationHistory(Long userId, NotificationStatus status,
                                                    NotificationType type, PageQuery pageQuery);

    /**
     * 根据关键词检索通知 (检索标题和内容, 分页)
     */
    PageResult<Notification> searchNotifications(String keyword, PageQuery pageQuery);

    /**
     * 获取用户发送审计日志 (按日志行分页, records 与 total 同为日志条数)
     */
    PageResult<NotificationLog> getLogs(Long userId, PageQuery pageQuery);

    /**
     * 将通知标记为已读 (幂等: 重复调用保留首次已读时间)。
     *
     * <p>通知不存在时抛 BusinessException (RESOURCE_NOT_FOUND);
     * 不属于该用户或无安全上下文 (userId 为 null) 时抛 NO_PERMISSION (fail-closed)。
     */
    void markAsRead(Long notificationId, Long userId);
}