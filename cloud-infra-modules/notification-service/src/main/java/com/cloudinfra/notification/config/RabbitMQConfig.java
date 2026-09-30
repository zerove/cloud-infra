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

    /** 通知渠道分发队列 (issue 27 决策 2) */
    public static final String NOTIFICATION_DISPATCH_QUEUE = "notification.dispatch.queue";

    @Bean
    public Queue iotTelemetryQueue() {
        return QueueBuilder.durable("iot.telemetry.queue").build();
    }

    /**
     * 通知分发队列: 渠道投递的解耦与持久化载体。
     *
     * <p>相比原先的“事务提交后 → 内存线程池异步执行”, 消息落在 Broker 上,
     * 进程重启后仍可被消费。</p>
     *
     * <p><b>不依赖 MQ 自身的重试/DLQ</b>: 本项目未对原生
     * {@code @RabbitListener} 配置监听器级重试 (K8s 中的
     * {@code dlq-name} 属 Spring Cloud Stream 的 {@code user-events-in-0} 绑定,
     * 与本队列无关)。失败重投由 {@code NotificationDispatchRecoveryTask}
     * 依据 {@code dispatch_status} 承担, 见 issue 27 决策 2。
     */
    @Bean
    public Queue notificationDispatchQueue() {
        return QueueBuilder.durable(NOTIFICATION_DISPATCH_QUEUE).build();
    }

    @Bean
    public BeanPostProcessor connectionFactoryLazyInitializer() {
        return new BeanPostProcessor() {
            // Spring AMQP 的 CachingConnectionFactory 没有关闭自动重连的公开开关, 只能反射改;
            // 失败时保留容器默认值而不是阻断启动, 因此这里的 setAccessible 是刻意的。
            @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof CachingConnectionFactory ccf) {
                    try {
                        var field = ccf.getClass().getDeclaredField("autoStartup");
                        field.setAccessible(true);
                        field.setBoolean(ccf, false);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // 反射关闭失败时保留容器默认值, 不阻断容器创建
                        log.debug("关闭 RabbitListenerContainerFactory 自动启动失败", ignored);
                    }
                }
                return bean;
            }
        };
    }
}
