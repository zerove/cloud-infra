package com.cloudinfra.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * 专门用于系统模块测试的配置
 */
@Configuration
@ComponentScan(basePackages = "com.cloudinfra.system")
@Import(com.cloudinfra.system.config.MyBatisConfig.class)
@MapperScan("com.cloudinfra.system.mapper")
public class SystemTestConfig {
}
