package com.cloudinfra.search.mq;

import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.repository.DeviceTelemetryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryConsumer {
    private final DeviceTelemetryRepository telemetryRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "iot.telemetry.queue")
    @SuppressWarnings("unchecked")
    public void handleTelemetry(byte[] payload) {
        try {
            // 1. 解析 Payload
            // 假设消息格式为: { "deviceId": "...", "data": { ... } }
            // 如果 Payload 只是纯数据，则需要从 RoutingKey 获取 deviceId (此处简化处理)
            Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            
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