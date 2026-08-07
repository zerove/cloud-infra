package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 调度通知任务
 * 用于存储定时发送的通知请求
 */
@Data
@TableName("sys_notification_schedule")
public class ScheduledNotification {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String templateCode;
    private String paramsJson; // 存储为 JSON 字符串
    private String type;
    private LocalDateTime scheduleTime;
    private Boolean processed = false;

    /**
     * 将 paramsJson 转换为 Map（复用调用方注入的单例 ObjectMapper，避免每次 new）
     */
    public Map<String, Object> getParams(ObjectMapper objectMapper) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}