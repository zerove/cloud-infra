package com.cloudinfra.framework.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.cloudinfra.framework.mybatis.handler.MyBatisPlusMetaObjectHandler;
import com.cloudinfra.framework.mybatis.interceptor.DataScopeInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
public class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页拦截器：启用 PG 方言分页（page/size 由 Page 参数驱动）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new DataScopeInterceptor());
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    public MyBatisPlusMetaObjectHandler myMetaObjectHandler() {
        return new MyBatisPlusMetaObjectHandler();
    }
}
