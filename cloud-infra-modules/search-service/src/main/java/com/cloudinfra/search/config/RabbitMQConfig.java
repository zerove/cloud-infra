package com.cloudinfra.search.config;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

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
                    }
                }
                return bean;
            }
        };
    }
}