package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NotificationLogDTO {
    private Long notificationId;
    private Long userId;
    private String channelCode;
    private String content;
    private String status;
    private LocalDateTime createTime;
    private String errorMessage;
}
