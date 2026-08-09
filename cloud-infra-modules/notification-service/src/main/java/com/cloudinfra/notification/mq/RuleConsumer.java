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
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 规则触发消费者 - 监听遥测数据并执行规则判定
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleConsumer {

    private final NotificationRuleService ruleService;
    private final INotificationService notificationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "iot.telemetry.queue")
    @SuppressWarnings("unchecked")
    public void handleTelemetry(byte[] payload) {
        try {
            // 1. 解析遥测数据
            Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
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
                    NotificationSendRequest request = new NotificationSendRequest(
                        rule.getTargetUserId(),
                        null,
                        null,
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
        } catch (Exception e) {
            log.error("规则判定链路异常: {}", e.getMessage());
        }
    }
}