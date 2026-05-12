package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class NotificationResponse implements Serializable {
    private Long notificationId;
    private String status;
}
