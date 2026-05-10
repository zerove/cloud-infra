package com.cloudinfra.notification.mq;

import com.cloudinfra.notification.service.INotificationService;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final ObjectMapper objectMapper;

    @Bean
    public Consumer<String> notificationConsumer() {
        return message -> {
            log.info("接收到通知消息: {}", message);
            try {
                JsonNode node = objectMapper.readTree(message);
                String type = node.get("type").asText();
                String target = node.get("target").asText();
                String content = node.get("content").asText();

                if ("email".equalsIgnoreCase(type)) {
                    String subject = node.has("subject") ? node.get("subject").asText() : "系统通知";
                    notificationService.sendEmail(target, subject, content);
                } else if ("sms".equalsIgnoreCase(type)) {
                    notificationService.sendSms(target, content);
                } else {
                    log.warn("未知的通知类型: {}", type);
                }
            } catch (Exception e) {
                log.error("处理通知失败: {}", message, e);
            }
        };
    }
}
