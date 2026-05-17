package com.cloudinfra.notification.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.cloudinfra.notification.mapper")
public class MyBatisConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        return new MybatisPlusInterceptor();
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(javax.sql.DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }

    @Bean
    public org.mybatis.spring.mapper.MapperFactoryBean<com.cloudinfra.notification.mapper.NotificationMapper> notificationMapper(SqlSessionFactory sqlSessionFactory) {
        org.mybatis.spring.mapper.MapperFactoryBean<com.cloudinfra.notification.mapper.NotificationMapper> factoryBean = new org.mybatis.spring.mapper.MapperFactoryBean<>(com.cloudinfra.notification.mapper.NotificationMapper.class);
        factoryBean.setSqlSessionFactory(sqlSessionFactory);
        return factoryBean;
    }
}
