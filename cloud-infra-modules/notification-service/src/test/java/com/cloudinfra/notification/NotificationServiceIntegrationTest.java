package com.cloudinfra.notification;

import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.repository.NotificationRepository;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@org.springframework.test.context.ActiveProfiles("test")
public class NotificationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private StreamBridge streamBridge;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    public void testFullNotificationFlow() throws InterruptedException {
        NotificationRequest request = new NotificationRequest();
        request.setUserId(1L);
        request.setTarget("test@example.com");
        request.setTitle("测试标题");
        request.setContent("测试内容");
        request.setType(NotificationType.EMAIL);

        streamBridge.send("notificationConsumer-in-0", request);

        // Wait for asynchronous processing
        Thread.sleep(2000);

        List<Notification> notifications = notificationRepository.findHistory(1L, null, null, 1, 10);
        assertFalse(notifications.isEmpty(), "Notification should be persisted in DB");
    }
}
