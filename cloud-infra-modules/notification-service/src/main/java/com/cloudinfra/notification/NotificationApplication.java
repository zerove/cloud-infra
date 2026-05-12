package com.cloudinfra.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.mybatis.spring.annotation.MapperScan;

@EnableDiscoveryClient
@MapperScan("com.cloudinfra.notification.mapper")
@SpringBootApplication(scanBasePackages = "com.cloudinfra")
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
