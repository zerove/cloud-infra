package com.cloudinfra.search.mq;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列声明。
 * <p>
 * 通过声明 {@link Queue} Bean，由自动配置中的 RabbitAdmin 在连接就绪时自动
 * (幂等) 声明队列，避免 @RabbitListener 对不存在的字符串队列做被动声明而失败。
 */
@Configuration
public class RabbitConfig {

    /** IoT 遥测数据队列：system 模块生产，search/notification 模块消费 */
    public static final String TELEMETRY_QUEUE = "iot.telemetry.queue";

    @Bean
    public Queue telemetryQueue() {
        return QueueBuilder.durable(TELEMETRY_QUEUE).build();
    }
}