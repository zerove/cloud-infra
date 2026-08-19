package com.cloudinfra.search.mq;

import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.repository.DeviceTelemetryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 遥测数据消费者
 * 消费幂等：基于 messageId（payload 中的 traceId/uuid）去重，TTL 24h
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryConsumer {
    private final DeviceTelemetryRepository telemetryRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitConfig.TELEMETRY_QUEUE, concurrency = "3-10")
    @SuppressWarnings("unchecked")
    public void handleTelemetry(byte[] payload) {
        try {
            // 1. 解析 Payload
            Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            
            String messageId = (String) data.getOrDefault("messageId", "");
            if (messageId.isEmpty()) {
                // 兜底：用 payload hash 作为幂等键
                messageId = "telemetry:" + java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(payload)).substring(0, 32);
            }
            // 幂等校验：SETNX 成功才处理
            String idempotentKey = "idempotent:telemetry:" + messageId;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("重复遥测消息已忽略: {}", messageId);
                return;
            }

            String deviceId = (String) data.getOrDefault("deviceId", "unknown");
            Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");

            // 2. 构建文档
            DeviceTelemetryDocument doc = new DeviceTelemetryDocument();
            doc.setId(UUID.randomUUID().toString());
            doc.setDeviceId(deviceId);
            doc.setTimestamp(LocalDateTime.now());
            doc.setRawPayload(new String(payload));
            doc.setMetrics(metrics);

            // 3. 持久化到 ES
            telemetryRepository.save(doc);
            
            log.debug("Indexed telemetry for device {}: {}", deviceId, metrics);
        } catch (Exception e) {
            log.error("Failed to process telemetry message", e);
        }
    }
}