package com.cloudinfra.notification.service.impl;

import com.cloudinfra.notification.service.INotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NotificationServiceImpl implements INotificationService {

    @Override
    public void sendEmail(String to, String subject, String content) {
        log.info("Sending Email to: {}, Subject: {}, Content: {}", to, subject, content);
        // Integrate with spring-boot-starter-mail here
    }

    @Override
    public void sendSms(String phone, String content) {
        log.info("Sending SMS to: {}, Content: {}", phone, content);
        // Integrate with SMS provider (e.g. Aliyun/Tencent) here
    }
}
