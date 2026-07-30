package com.cloudinfra.search.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 设备遥测数据 ES 文档
 */
@Data
@Document(indexName = "iot_telemetry")
public class DeviceTelemetryDocument {
    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String deviceId;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime timestamp;

    /**
     * 原始遥测数据
     */
    @Field(type = FieldType.Text)
    private String rawPayload;

    /**
     * 解析后的结构化指标 (例如: {"temp": 25.5, "humidity": 60})
     */
    @Field(type = FieldType.Object)
    private Map<String, Object> metrics;
}