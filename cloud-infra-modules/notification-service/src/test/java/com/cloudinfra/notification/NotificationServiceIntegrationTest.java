package com.cloudinfra.notification;

import com.cloudinfra.notification.repository.NotificationRepository;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
public class NotificationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    public void testFullNotificationFlow() {
        // Integration test logic ...
    }
}
