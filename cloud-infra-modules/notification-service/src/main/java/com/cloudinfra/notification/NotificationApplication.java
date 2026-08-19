package com.cloudinfra.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.cloudinfra.api")
@EnableAsync
@EnableTransactionManagement
@SpringBootApplication
// 全量扫描 com.cloudinfra (含 framework/common 组件), 排除 starter 包避免重复注册
@ComponentScan(
    basePackages = "com.cloudinfra",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.cloudinfra\\.starter\\..*")
    }
)
@Import({RedissonConfig.class, TracingAutoConfiguration.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NotificationApplication.class);
        app.setLazyInitialization(true);
        app.run(args);
    }
}
