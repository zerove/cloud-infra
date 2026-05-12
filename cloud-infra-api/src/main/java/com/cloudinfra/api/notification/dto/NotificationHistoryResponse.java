package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class NotificationHistoryResponse implements Serializable {
    private Long id;
    private String type;
    private String title;
    private String content;
    private Integer status; // 0-unread, 1-read
    private LocalDateTime createTime;
}
