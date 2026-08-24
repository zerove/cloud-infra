package com.cloudinfra.framework.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.cloudinfra.framework.mybatis.handler.MyBatisPlusMetaObjectHandler;
import com.cloudinfra.framework.mybatis.interceptor.DataScopeInterceptor;
import org.apache.ibatis.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
public class MybatisPlusConfig {

    static {
        // GraalVM Native Image 下 MyBatis LogFactory 的自动选型依赖反射探测
        // （依次尝试 slf4j/log4j2 等），探测过程会抛 NPE。显式锁定 Slf4J 实现。
        LogFactory.useSlf4jLogging();
        // GraalVM Native Image 下 mybatis 默认的 JavassistProxyFactory 构造器会
        // classForName 探测内嵌 shaded javassist 的 ProxyFactory。该包无任何静态
        // 引用，可达性分析不会将其纳入镜像，导致启动即抛
        // "Cannot enable lazy loading because Javassist is not available"。
        // 此处显式建立静态引用，强制纳入镜像。
        try {
            Class.forName("org.apache.ibatis.javassist.util.proxy.ProxyFactory");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("mybatis shaded javassist missing", e);
        }
    }

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
