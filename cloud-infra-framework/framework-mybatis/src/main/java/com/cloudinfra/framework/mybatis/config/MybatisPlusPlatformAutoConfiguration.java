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
// 类级**不再**加任何 DataSource 条件注解 (2026-10-02 修正)。
//
// 症状: 子进程(system-module exec.jar)启动即失败, Mapper 报
//   "Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required";
//   启动日志里 DataSource/Flyway/Hikari 一个字都没有。
//
// 根因: 原先类上有 @ConditionalOnSingleCandidate(DataSource.class)。
// Spring 的 ConditionEvaluationReport 写着
//   MybatisPlusPlatformAutoConfiguration: Did not match:
//     @ConditionalOnSingleCandidate (types: javax.sql.DataSource) did not find any beans
// 而**同一个报告里** dataSource bean 已经存在 —— 其他用 @ConditionalOnBean(DataSource)
// 的自动配置(DataSourcePoolMetricsAutoConfiguration、FlywayAutoConfiguration)
// 都写着 "found bean 'dataSource'"。
//
// 即: bean **定义**已存在, 但 @ConditionalOnSingleCandidate 在自动配置求值阶段
// 判定为"无单候选"(它需要候选 bean 完成 @Primary 解析, 而此时另一个 jar 里的
// DataSourceOptimizationAutoConfiguration 刚注册定义、尚未实例化), 于是整支被跳过。
//
// 为什么不用 @ConditionalOnBean 替换: 同样依赖跨 jar 求值顺序,
// 在局部 ApplicationContextRunner 下会判为不满足(已实测), 属于换一种方式再踩坑。
// 真正需要 DataSource 的是 sqlSessionFactory(@Bean 方法), 它已用
// `DataSource dataSource` 参数注入 —— 缺 bean 时 Spring 在**实例化阶段**报
// 清晰的 NoSuchBeanDefinitionException, 比类级条件静默跳过整支要好得多。
//
// 为什么此前从未暴露: 该缺陷只在**独立进程 + 完整自动配置链**下出现;
// 依赖 ApplicationContextRunner 的局部测试走不到跨 jar 的排序路径。
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
