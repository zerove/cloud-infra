package com.cloudinfra.notification.mq;

import com.cloudinfra.notification.domain.NotificationRule;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.NotificationRuleService;
import com.cloudinfra.notification.util.RuleEvaluator;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 规则触发消费者 - 监听遥测数据并执行规则判定
 * 消费幂等：基于 messageId（payload 中的 traceId/uuid）去重，TTL 24h
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleConsumer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RuleConsumer.class);

    private final NotificationRuleService ruleService;
    private final INotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = "iot.telemetry.queue", concurrency = "3-10")
    @SuppressWarnings("unchecked")
    public void handleTelemetry(byte[] payload) {
        String messageId = "";
        try {
            // 1. 解析遥测数据
            Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            messageId = (String) data.getOrDefault("messageId", "");
            if (messageId.isEmpty()) {
                // 兜底：用 payload hash 作为幂等键
                try {
                    messageId = "rule:" + java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(payload)).substring(0, 32);
                } catch (NoSuchAlgorithmException e) {
                    messageId = "rule:" + System.currentTimeMillis();
                }
            }
            // 幂等校验：SETNX 成功才处理
            String idempotentKey = "idempotent:rule:" + messageId;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("重复消息已忽略: {}", messageId);
                return;
            }

            String deviceId = (String) data.getOrDefault("deviceId", "unknown");
            Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");

            if (metrics == null) {
                return;
            }

            // 2. 获取该设备的活跃规则
            List<NotificationRule> activeRules = ruleService.getActiveRulesForDevice(deviceId);
            if (activeRules.isEmpty()) {
                return;
            }

            // 3. 逐条判定规则
            for (NotificationRule rule : activeRules) {
                String metricName = rule.getMetric();
                Object valueObj = metrics.get(metricName);

                if (valueObj == null) {
                    continue;
                }

                // 将值转换为 Double 统一判定
                Double currentValue = Double.valueOf(valueObj.toString());

                if (RuleEvaluator.evaluate(rule, currentValue)) {
                    log.info("[Rule-Trigger] 规则 {} 触发! 设备: {}, 指标: {} = {} (阈值: {} {})", 
                        rule.getId(), deviceId, metricName, currentValue, rule.getOperator(), rule.getThreshold());

                    // 4. 触发通知
                    // 无模版时必须提供兜底标题/内容，否则 sendNotification 的
                    // validate() 会拒绝（Title is required when templateCode
                    // is not provided），导致告警静默丢失（消息进 DLQ）
                    String alertTitle = rule.getTemplateCode() != null && !rule.getTemplateCode().isBlank()
                        ? null : "设备告警";
                    String alertContent = rule.getTemplateCode() != null && !rule.getTemplateCode().isBlank()
                        ? null : String.format("设备 %s 的指标 %s 当前值 %s 已达到阈值 %s",
                            deviceId, metricName, currentValue, rule.getThreshold());
                    NotificationSendRequest request = new NotificationSendRequest(
                        rule.getTargetUserId(),
                        alertTitle,
                        alertContent,
                        NotificationType.ALERT, // 告警类通知
                        null,
                        rule.getTemplateCode(),
                        Map.of(
                            "deviceId", deviceId,
                            "metric", metricName,
                            "value", currentValue,
                            "threshold", rule.getThreshold()
                        ),
                        null
                    );
                    notificationService.sendNotification(request);
                }
            }
        } catch (IOException e) {
            log.error("解析遥测数据失败，消息将进入 DLQ: {}", messageId, e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("规则判定链路异常，消息将进入 DLQ: {}", messageId, e);
            throw e;
        }
    }
}