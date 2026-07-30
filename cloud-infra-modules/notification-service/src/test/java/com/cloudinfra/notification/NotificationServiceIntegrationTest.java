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
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@org.springframework.test.context.ActiveProfiles("test")
public class NotificationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private StreamBridge streamBridge;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    public void testFullNotificationFlow() {
        NotificationRequest request = new NotificationRequest(
            1L,
            NotificationType.EMAIL,
            "测试标题",
            "测试内容",
            "test@example.com"
        );

        streamBridge.send("notificationConsumer-in-0", request);

        // Wait for asynchronous processing
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> {
                List<Notification> results = notificationRepository.findHistory(1L, null, null, 1, 10);
                return !results.isEmpty();
            });
    }
}
