package com.cloudinfra.notification.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.cloudinfra.notification.mapper")
public class MyBatisConfig {
    // MybatisPlusInterceptor 由 framework-mybatis 的 MybatisPlusConfig 统一注册。
}
