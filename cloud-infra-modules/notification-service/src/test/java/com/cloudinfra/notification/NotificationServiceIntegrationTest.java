package com.cloudinfra.notification;

import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.repository.NotificationRepository;
import com.cloudinfra.test.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class NotificationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private NotificationRepository notificationRepository;

    @AfterEach
    public void cleanup() {
        notificationRepository.remove(new QueryWrapper<>());
    }

    @Test
    public void testFullNotificationFlow() {
        // 1. Send Notification
        NotificationRequest request = new NotificationRequest();
        request.setUserId(1L);
        request.setType(NotificationType.EMAIL);
        request.setTitle("Integration Test Title");
        request.setContent("Integration Test Content");
        request.setTarget("test@example.com");

        webTestClient.post()
                .uri("/notification/send")
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Result.class)
                .value(result -> assertThat(result.getMessage()).isNotNull());

        // 2. Wait for MQ processing using Awaitility
        await().atMost(Duration.ofSeconds(5))
               .until(() -> !notificationRepository.findHistory(1L, NotificationStatus.UNREAD, NotificationType.EMAIL, 1, 10).isEmpty());

        // 3. Verify DB persistence
        List<Notification> history = notificationRepository.findHistory(1L, NotificationStatus.UNREAD, NotificationType.EMAIL, 1, 10);
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).getTitle()).isEqualTo("Integration Test Title");

        // 4. Test Mark as Read
        Long id = history.get(0).getId();
        webTestClient.put()
                .uri("/notification/read/" + id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Result.class);
        
        Notification updated = notificationRepository.getById(id);
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.READ);
    }
}
