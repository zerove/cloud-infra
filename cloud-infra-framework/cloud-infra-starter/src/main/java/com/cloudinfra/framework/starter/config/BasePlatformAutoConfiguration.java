package com.cloudinfra.framework.starter.config;

import com.cloudinfra.framework.mybatis.handler.MyBatisPlusMetaObjectHandler;
import com.cloudinfra.framework.mybatis.interceptor.DataScopeInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "com.cloudinfra.framework")
public class BasePlatformAutoConfiguration {

    @Bean
    public MyBatisPlusMetaObjectHandler metaObjectHandler() {
        return new MyBatisPlusMetaObjectHandler();
    }

    @Bean
    public DataScopeInterceptor dataScopeInterceptor() {
        return new DataScopeInterceptor();
    }
}
