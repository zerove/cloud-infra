package com.cloudinfra.notification.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Consumer;

@Slf4j
@Configuration
public class NotificationConsumer {

    @Bean
    public Consumer<String> notificationConsumer() {
        return message -> {
            log.info("Received notification message: {}", message);
            // In a real scenario, we would parse the message and send SMS/Email
            try {
                processNotification(message);
            } catch (Exception e) {
                log.error("Failed to process notification", e);
                // In a production environment, we might want to throw an exception
                // to trigger RabbitMQ retry/DLQ logic.
            }
        };
    }

    private void processNotification(String content) {
        log.info("Processing notification content: {}", content);
        // Mock sending...
    }
}
