package com.cloudinfra.framework.mybatis.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.cloudinfra.framework.mybatis.crypto.EncryptedTypeHandler;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * MyBatis-Plus 平台自动配置
 *
 * 背景: MyBatis-Plus 3.5.x 的官方 starter (mybatis-plus-spring-boot3-starter)
 * 与 Spring Boot 4 不兼容: 其 MybatisPlusAutoConfiguration 的
 * {@code @AutoConfigureAfter} 仍引用 Spring Boot 3 旧类路径
 * (org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration)。
 * Spring Boot 4 的 AnnotationBeanNameGenerator 在注册导入的配置类时会解析
 * 注解中的 Class 属性值并加载该类, 旧路径类不存在导致启动失败。
 *
 * 因此本平台不使用官方 starter, 而是基于 mybatis-plus 核心依赖手动装配:
 * - SqlSessionFactory (MybatisSqlSessionFactoryBean, 含分页/数据权限插件与加密 TypeHandler)
 * - SqlSessionTemplate
 * - MapperScannerConfigurer (扫描 com.cloudinfra.*.mapper)
 */
@AutoConfiguration
@AutoConfigureAfter(com.cloudinfra.framework.web.config.DataSourceOptimizationAutoConfiguration.class)
@ConditionalOnClass({ SqlSessionFactory.class, MybatisSqlSessionFactoryBean.class })
@ConditionalOnSingleCandidate(DataSource.class)
public class MybatisPlusPlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SqlSessionFactory sqlSessionFactory(
        DataSource dataSource,
        @Value("${mybatis-plus.type-aliases-package:com.cloudinfra.**.domain,com.cloudinfra.**.domain.model}") String typeAliasesPackage,
        @Value("${mybatis-plus.mapper-locations:classpath*:/mapper/**/*.xml}") String mapperLocations,
        ObjectProvider<MybatisPlusInterceptor> interceptorProvider,
        ObjectProvider<TypeHandler<?>> typeHandlerProvider,
        ObjectProvider<org.apache.ibatis.plugin.Interceptor[]> pluginProvider,
        ObjectProvider<com.baomidou.mybatisplus.core.handlers.MetaObjectHandler> metaObjectHandlerProvider
    ) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage(typeAliasesPackage);

        // 元数据填充处理器 (审计字段自动填充)
        metaObjectHandlerProvider.ifAvailable(handler -> {
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(handler);
            factoryBean.setGlobalConfig(globalConfig);
        });

        // Mapper XML 位置
        Resource[] resources = new PathMatchingResourcePatternResolver()
            .getResources(mapperLocations);
        if (resources.length > 0) {
            factoryBean.setMapperLocations(resources);
        }

        // 组合插件: MyBatis-Plus 拦截器 + 自定义插件
        List<Interceptor> interceptors = new java.util.ArrayList<>();
        interceptorProvider.ifAvailable(interceptors::add);
        Interceptor[] customPlugins = pluginProvider.getIfAvailable();
        if (customPlugins != null) {
            for (Interceptor p : customPlugins) {
                if (!(p instanceof MybatisPlusInterceptor)) {
                    interceptors.add(p);
                }
            }
        }
        if (!interceptors.isEmpty()) {
            factoryBean.setPlugins(interceptors.toArray(new Interceptor[0]));
        }

        // 注册加密 TypeHandler
        List<TypeHandler<?>> handlers = new java.util.ArrayList<>();
        typeHandlerProvider.orderedStream().forEach(handlers::add);
        if (!handlers.isEmpty()) {
            factoryBean.setTypeHandlers(handlers.toArray(new TypeHandler[0]));
        }

        return factoryBean.getObject();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * Mapper 接口扫描: 平台统一约定 com.cloudinfra.*.mapper
     */
    @Bean
    @ConditionalOnMissingBean
    public MapperScannerConfigurer mapperScannerConfigurer() {
        MapperScannerConfigurer configurer = new MapperScannerConfigurer();
        configurer.setBasePackage("com.cloudinfra.*.mapper");
        configurer.setSqlSessionFactoryBeanName("sqlSessionFactory");
        return configurer;
    }

    /**
     * 字段级加密 TypeHandler: 仅在配置了加密密钥时注册
     * (cloud-infra.crypto.key 或 ENCRYPTION_KEY 环境变量)
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cloud-infra.crypto.key", matchIfMissing = false)
    public EncryptedTypeHandler encryptedTypeHandler(
        @Value("${cloud-infra.crypto.key:${ENCRYPTION_KEY:}}") String keyBase64
    ) {
        return new EncryptedTypeHandler(keyBase64);
    }
}
