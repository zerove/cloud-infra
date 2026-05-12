package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.domain.Notification;
import java.util.List;

/**
 * 通知服务接口，定义了通知的发送、存储、查询及状态更新等核心业务逻辑。
 */
public interface INotificationService {
    void sendEmail(String to, String subject, String content);
    void sendSms(String phone, String content);
    
    void saveNotification(Long userId, NotificationType type, String title, String content);
    List<Notification> getHistory(Long userId, NotificationStatus status, NotificationType type);
    void markAsRead(Long notificationId);
}
