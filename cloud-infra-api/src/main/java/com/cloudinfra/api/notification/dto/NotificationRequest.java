package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class NotificationRequest implements Serializable {
    private Long userId;
    private String type; // SYSTEM, MESSAGE, ALERT
    private String title;
    private String content;
    private String target; // Email or Phone
}
