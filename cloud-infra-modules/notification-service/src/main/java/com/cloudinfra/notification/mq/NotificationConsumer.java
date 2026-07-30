package com.cloudinfra.notification.mq;

import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.service.INotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final INotificationService notificationService;

    public Consumer<NotificationRequest> notificationConsumer() {
        return request -> {
            log.info("接收到通知消息: {}", request);
            try {
                var sendRequest = new NotificationSendRequest(
                    request.userId(),
                    request.title(),
                    request.content(),
                    request.type(),
                    null,
                    null,
                    null,
                    null
                );
                notificationService.sendNotification(sendRequest);
            } catch (Exception e) {
                log.error("处理通知失败: {}", request, e);
            }
        };
    }
}
