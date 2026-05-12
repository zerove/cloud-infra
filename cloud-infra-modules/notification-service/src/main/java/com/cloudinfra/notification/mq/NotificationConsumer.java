package com.cloudinfra.notification.mq;

import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.service.INotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Consumer;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class NotificationConsumer {

    private final INotificationService notificationService;

    @Bean
    public Consumer<NotificationRequest> notificationConsumer() {
        return request -> {
            log.info("接收到通知消息: {}", request);
            try {
                String target = request.getTarget();
                String content = request.getContent();
                String title = request.getTitle() != null ? request.getTitle() : "系统通知";

                if (NotificationType.EMAIL.equals(request.getType())) {
                    notificationService.sendEmail(target, title, content);
                } else if (NotificationType.SMS.equals(request.getType())) {
                    notificationService.sendSms(target, content);
                } else {
                    log.warn("不支持的通知类型或未指定发送渠道: {}", request.getType());
                }

                // PERSISTENCE: Save to DB after sending
                notificationService.saveNotification(request.getUserId(), request.getType(), title, content);

            } catch (Exception e) {
                log.error("处理通知失败: {}", request, e);
            }
        };
    }
}
