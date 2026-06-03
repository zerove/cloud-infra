package com.cloudinfra.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

@EnableDiscoveryClient
@EnableAsync
@EnableTransactionManagement
@SpringBootApplication(scanBasePackages = {"com.cloudinfra"})
@Import({RedissonConfig.class, TracingAutoConfiguration.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NotificationApplication.class);
        app.setLazyInitialization(true);
        app.run(args);
    }
}
