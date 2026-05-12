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
    
    /**
     * 保存通知记录到数据库。
     *
     * @param userId   用户ID
     * @param type     通知类型
     * @param title    通知标题
     * @param content  通知内容
     */
    void saveNotification(Long userId, NotificationType type, String title, String content);

    /**
     * 根据用户ID、状态和类型查询通知历史记录。
     *
     * @param userId   用户ID
     * @param status   通知状态（已读/未读）
     * @param type     通知类型
     * @param page     页码
     * @param size     每页数量
     * @return 通知历史记录列表
     */
    List<Notification> getHistory(Long userId, NotificationStatus status, NotificationType type, Integer page, Integer size);

    /**
     * 将指定ID的通知标记为已读。
     *
     * @param notificationId 通知记录ID
     */
    void markAsRead(Long notificationId);
}
