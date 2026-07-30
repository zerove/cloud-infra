package com.cloudinfra.notification.service;

import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知调度引擎 - 负责定时通知的扫描与触发行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationScheduler {

    private final ScheduledNotificationMapper scheduleMapper;
    private final INotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 每分钟扫描一次到期的通知任务
     */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void processScheduledNotifications() {
        log.info("开始扫描到期通知任务...");
        
        // 1. 查找所有待处理且已到期的通知
        List<ScheduledNotification> dueTasks = scheduleMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduledNotification>()
                .eq(ScheduledNotification::getProcessed, false)
                .le(ScheduledNotification::getScheduleTime, LocalDateTime.now())
        );

        if (dueTasks.isEmpty()) {
            log.info("当前无到期任务。");
            return;
        }

        log.info("发现 {} 个到期任务，准备发送...", dueTasks.size());

        for (ScheduledNotification task : dueTasks) {
            try {
                // 2. 将调度任务转换为发送请求
                NotificationSendRequest request = new NotificationSendRequest(
                    task.getUserId(),
                    null, // 模版发送不需要 title
                    null, // 模版发送不需要 content
                    com.cloudinfra.api.notification.enums.NotificationType.valueOf(task.getType()),
                    null, // 使用模版默认渠道
                    task.getTemplateCode(),
                    task.getParams(),
                    null // 已到期，无需再次调度
                );

                // 3. 执行发送
                notificationService.sendNotification(request);
                
                // 4. 标记为已处理
                task.setProcessed(true);
                scheduleMapper.updateById(task);
                
                log.info("调度任务 {} 发送成功", task.getId());
            } catch (Exception e) {
                log.error("调度任务 {} 执行失败: {}", task.getId(), e.getMessage());
            }
        }
    }
}