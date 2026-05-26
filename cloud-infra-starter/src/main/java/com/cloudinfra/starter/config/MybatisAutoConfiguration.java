package com.cloudinfra.starter.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(org.mybatis.spring.SqlSessionFactoryBean.class)
@MapperScan("com.cloudinfra.*.mapper")
public class MybatisAutoConfiguration {
}
