package com.cloudinfra.system.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 智能服务工单
 */
@Data
public class ServiceTicket {
    private Long id;
    private String ticketNo;
    private String title;
    private String content;
    private Integer priority; // 1: 低, 2: 中, 3: 高, 4: 紧急
    private String status; // OPEN, PROCESSING, RESOLVED, CLOSED
    private Long requesterId;
    private Long assigneeId; // 处理人
    private Long assetId;    // 关联的资源/设备ID
    private LocalDateTime createTime;
    private LocalDateTime resolveTime;
}