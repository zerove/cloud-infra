package com.cloudinfra.notification.service.impl;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.repository.NotificationRepository;
import com.cloudinfra.notification.service.INotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知服务实现类，负责具体通知渠道（邮件、短信）的投递逻辑及通知记录的持久化管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    public void sendEmail(String to, String subject, String content) {
        log.info("Sending Email to: {}, Subject: {}, Content: {}", to, subject, content);
    }

    @Override
    public void sendSms(String phone, String content) {
        log.info("Sending SMS to: {}, Content: {}", phone, content);
    }

    @Override
    @Transactional
    public void saveNotification(Long userId, NotificationType type, String title, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setStatus(NotificationStatus.UNREAD);
        notificationRepository.save(notification);
        log.info("Notification saved for user {}: {}", userId, title);
    }

    @Override
    public List<Notification> getHistory(Long userId, NotificationStatus status, NotificationType type) {
        return notificationRepository.findHistory(userId, status, type);
    }

    @Override
    @Transactional
    public void markAsRead(Long notificationId) {
        Notification notification = notificationRepository.getById(notificationId);
        if (notification != null) {
            notification.setStatus(NotificationStatus.READ);
            notification.setReadTime(LocalDateTime.now());
            notificationRepository.updateById(notification);
        }
    }
}
