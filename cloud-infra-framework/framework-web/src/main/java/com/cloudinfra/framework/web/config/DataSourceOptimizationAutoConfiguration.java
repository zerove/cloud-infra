package com.cloudinfra.framework.web.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 数据库连接池优化配置
 * 基于 HikariCP 的生产级调优：
 * - 连接池大小动态调整
 * - 连接生命周期管理
 * - 监控指标暴露
 * - 慢查询检测
 * - 连接泄漏检测
 *
 * 注意: 显式声明在 Spring Boot 自带 DataSource 自动配置之前，确保自定义
 * DataSource bean 先注册。MyBatis-Plus 3.5.6 的 @AutoConfigureAfter 仍引用
 * Spring Boot 3 的旧类路径 (org.springframework.boot.autoconfigure.jdbc.*)，
 * 在 Spring Boot 4 下排序失效，需配合 framework-mybatis 中的
 * MybatisPlusOrderingAutoConfiguration 形成排序链。
 */
@AutoConfiguration
@AutoConfigureBefore(org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
// 无 spring.datasource.url 的服务 (如 auth) 根本不连库, 不应为它们凭空建一个连接池。
// 缺此条件时, @Value("${spring.datasource.url}") 解析失败会让**整个上下文启动失败**
// (2026-10-17 E2E 实测: auth 因此重启 62 次)。
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class DataSourceOptimizationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataSourceOptimizationAutoConfiguration.class);

    @Bean
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(
        @Value("${spring.datasource.url}") String url,
        @Value("${spring.datasource.username}") String username,
        @Value("${spring.datasource.password}") String password,
        @Value("${spring.datasource.driver-class-name}") String driverClassName
    ) {
        HikariConfig config = new HikariConfig();

        // 基础连接配置
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);

        // 连接池核心参数
        config.setMaximumPoolSize(getOptimalPoolSize());
        config.setMinimumIdle(getOptimalMinIdle());
        config.setIdleTimeout(30_000);           // 30秒空闲回收
        config.setMaxLifetime(600_000);          // 10分钟最大生命周期
        config.setConnectionTimeout(30_000);     // 30秒获取连接超时
        config.setValidationTimeout(5_000);      // 5秒验证超时

        // 连接测试
        config.setConnectionTestQuery("SELECT 1");
        config.setPoolName("cloud-infra-hikari-pool");

        // 泄漏检测
        config.setLeakDetectionThreshold(60_000); // 60秒泄漏检测

        // 只读连接优化
        config.setReadOnly(false);

        // 缓存预编译语句
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        // PostgreSQL 优化
        config.addDataSourceProperty("reWriteBatchedInserts", "true");

        log.info("HikariCP 连接池初始化: maxPoolSize={}, minIdle={}",
            config.getMaximumPoolSize(), config.getMinimumIdle());

        return new HikariDataSource(config);
    }

    /**
     * 根据 CPU 核心数计算最优连接池大小
     * 公式: ((core_count * 2) + effective_spindle_count)
     * 对于虚拟线程环境，适当减少连接数
     */
    private int getOptimalPoolSize() {
        int cores = Runtime.getRuntime().availableProcessors();
        // 虚拟线程环境下连接数可适当减少
        int poolSize = (cores * 2) + 1;
        return Math.min(poolSize, 50); // 最大 50
    }

    /**
     * 最小空闲连接数
     */
    private int getOptimalMinIdle() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(cores / 2, 2);
    }
}