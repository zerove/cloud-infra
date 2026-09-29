package com.cloudinfra.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue iotTelemetryQueue() {
        return QueueBuilder.durable("iot.telemetry.queue").build();
    }

    @Bean
    public BeanPostProcessor connectionFactoryLazyInitializer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof CachingConnectionFactory ccf) {
                    try {
                        var field = ccf.getClass().getDeclaredField("autoStartup");
                        field.setAccessible(true);
                        field.setBoolean(ccf, false);
                    } catch (Exception ignored) {
                        // 反射关闭失败时保留容器默认值, 不阻断容器创建
                        log.debug("关闭 RabbitListenerContainerFactory 自动启动失败", ignored);
                    }
                }
                return bean;
            }
        };
    }
}
