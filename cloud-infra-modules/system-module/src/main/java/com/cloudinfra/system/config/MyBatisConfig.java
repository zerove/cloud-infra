package com.cloudinfra.system.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.cloudinfra.system.mapper")
public class MyBatisConfig {
    // MybatisPlusInterceptor (含 DataScopeInterceptor) 由
    // framework-mybatis 的 MybatisPlusConfig @AutoConfiguration 统一注册，
    // 此处仅负责 Mapper 包扫描。
}
