package com.cloudinfra.search.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

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