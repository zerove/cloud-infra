package com.cloudinfra.notification.service;

import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import java.util.List;

public interface INotificationService {
    /**
     * 发送通知 (支持立即发送或定时调度)
     */
    void sendNotification(NotificationSendRequest request);

    /**
     * 获取用户通知历史
     */
    List<Notification> getNotificationHistory(Long userId);

    /**
     * 根据关键词检索通知 (检索标题和内容)
     */
    List<Notification> searchNotifications(String keyword);

    /**
     * 获取用户发送审计日志
     */
    List<NotificationLog> getLogs(Long userId);
}