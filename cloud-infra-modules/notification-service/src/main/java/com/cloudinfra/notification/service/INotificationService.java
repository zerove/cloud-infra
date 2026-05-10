package com.cloudinfra.notification.service;

public interface INotificationService {
    void sendEmail(String to, String subject, String content);
    void sendSms(String phone, String content);
}
