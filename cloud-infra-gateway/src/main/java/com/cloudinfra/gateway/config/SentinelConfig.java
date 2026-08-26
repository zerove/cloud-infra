package com.cloudinfra.gateway.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.init.InitExecutor;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.Properties;

/**
 * Sentinel Nacos 数据源配置
 * 从 Nacos 动态拉取限流/熔断/热点/系统/授权规则
 *
 * 注意：
 * 1. 全局 spring.main.lazy-initialization=true 时，Sentinel 数据源 bean 必须显式
 *    @Lazy(false) 强制非懒加载，否则规则永远不会加载。
 * 2. Sentinel 的 InitExecutor 走 SPI (META-INF/services) 加载 InitFunc，
 *    不会调用 Spring 容器中的 InitFunc bean。因此这里改为实现
 *    SmartInitializingSingleton，在全部单例 bean 就绪后主动注册规则源。
 */
@Configuration
@Lazy(false)
public class SentinelConfig implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SentinelConfig.class);

    @Value("${sentinel.datasource.flow.nacos.server-addr:cloud-nacos:8848}")
    private String serverAddr;

    @Value("${sentinel.datasource.flow.nacos.group-id:DEFAULT_GROUP}")
    private String groupId;

    /** Sentinel Dashboard 地址（心跳上报，如 cloud-sentinel:8858） */
    @Value("${sentinel.transport.dashboard:}")
    private String dashboardServer;

    /** Sentinel API 端口（心跳上报端口，默认 8719） */
    @Value("${sentinel.transport.port:8719}")
    private int transportPort;

    /** 应用名（Sentinel Dashboard 展示名称） */
    @Value("${spring.application.name:cloud-infra-gateway}")
    private String appName;

    /**
     * F3: 五个规则数据源共享一个 ConfigService。
     * NacosDataSource(String,...) 构造器内部每次调 NacosFactory.createConfigService
     * 新建 gRPC 连接（无共享池），5 个数据源串行建连曾是启动期 ~35s 的主因；
     * 改为单例 ConfigService 后仅一次建连，后续数据源复用。
     */
    private volatile ConfigService sharedConfigService;

    /** 限流规则数据源（非懒加载 bean） */
    private final ObjectProvider<ReadableDataSource<String, List<FlowRule>>> flowRuleDataSource;

    /** 熔断规则数据源（非懒加载 bean） */
    private final ObjectProvider<ReadableDataSource<String, List<DegradeRule>>> degradeRuleDataSource;

    /** 热点参数限流规则数据源（非懒加载 bean） */
    private final ObjectProvider<ReadableDataSource<String, List<ParamFlowRule>>> paramFlowRuleDataSource;

    /** 系统规则数据源（非懒加载 bean） */
    private final ObjectProvider<ReadableDataSource<String, List<SystemRule>>> systemRuleDataSource;

    /** 授权规则数据源（非懒加载 bean） */
    private final ObjectProvider<ReadableDataSource<String, List<AuthorityRule>>> authorityRuleDataSource;

    public SentinelConfig(
            ObjectProvider<ReadableDataSource<String, List<FlowRule>>> flowRuleDataSource,
            ObjectProvider<ReadableDataSource<String, List<DegradeRule>>> degradeRuleDataSource,
            ObjectProvider<ReadableDataSource<String, List<ParamFlowRule>>> paramFlowRuleDataSource,
            ObjectProvider<ReadableDataSource<String, List<SystemRule>>> systemRuleDataSource,
            ObjectProvider<ReadableDataSource<String, List<AuthorityRule>>> authorityRuleDataSource) {
        this.flowRuleDataSource = flowRuleDataSource;
        this.degradeRuleDataSource = degradeRuleDataSource;
        this.paramFlowRuleDataSource = paramFlowRuleDataSource;
        this.systemRuleDataSource = systemRuleDataSource;
        this.authorityRuleDataSource = authorityRuleDataSource;

        log.info("=== SentinelConfig 类被实例化 ===");
    }

    /**
     * 字段注入完成后，将 transport 配置暴露给 Sentinel 心跳线程。
     * Sentinel 的心跳/API 端口依赖系统属性 (csp.sentinel.*)，
     * 与 Spring 属性体系脱节，必须显式写入 System properties。
     */
    @PostConstruct
    public void initTransport() {
        if (dashboardServer != null && !dashboardServer.isBlank()) {
            System.setProperty("csp.sentinel.dashboard.server", dashboardServer);
            System.setProperty("csp.sentinel.api.port", String.valueOf(transportPort));
            System.setProperty("csp.sentinel.app.name", appName);
            log.info("=== Sentinel 心跳配置: dashboard={}, port={}, appName={} ===",
                    dashboardServer, transportPort, appName);
        }
    }

    /**
     * 惰性共享 Properties：五个数据源共用同一份 Nacos 连接属性。
     * sentinel-datasource-nacos 1.8.9 的 NacosDataSource 构造器内部强制
     * NacosFactory.createConfigService（每次新建 gRPC 连接，无共享池），
     * 5 个数据源串行建连曾占网关启动耗时一半以上。这里构造完成后立即
     * 通过反射把各实例的 configService 字段替换为首个实例，实现单连接复用。
     */
    private volatile Properties sharedProperties;

    private synchronized Properties sharedProperties() {
        if (sharedProperties == null) {
            sharedProperties = new Properties();
            sharedProperties.setProperty("serverAddr", serverAddr);
            sharedProperties.setProperty("namespace", "public");
        }
        return sharedProperties;
    }

    /**
     * F3: 将数据源的 ConfigService 收敛为进程内单一实例。
     * 首个调用者正常创建（真实 gRPC 建连），后续实例的 configService 字段
     * 被反射替换为该实例后其自身新建的连接即刻被丢弃（可 GC）。
     */
    private static void shareConfigServiceAcross(ReadableDataSource<?, ?>... sources) {
        java.lang.reflect.Field field = null;
        try {
            field = com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource.class
                    .getDeclaredField("configService");
            field.setAccessible(true);
            Object primary = null;
            for (ReadableDataSource<?, ?> source : sources) {
                if (!(source instanceof com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource)) {
                    continue;
                }
                Object current = field.get(source);
                if (primary == null) {
                    primary = current;
                } else if (current != primary) {
                    // 丢弃重复连接，复用主实例（Nacos ConfigService 线程安全）
                    try {
                        ((com.alibaba.nacos.api.config.ConfigService) current).shutDown();
                    } catch (Exception ignore) {
                        // 冗余连接关闭失败不影响主流程
                    }
                    field.set(source, primary);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Sentinel 数据源 ConfigService 共享化失败", e);
        } finally {
            if (field != null) {
                field.setAccessible(false);
            }
        }
    }

    /**
     * 限流规则数据源
     */
    @Bean
    @Lazy(false)
    public ReadableDataSource<String, List<FlowRule>> flowRuleDataSource(
            @Value("${sentinel.datasource.flow.nacos.data-id}") String dataId) {
        log.info("=== 创建 flowRuleDataSource, dataId: {} ===", dataId);
        Converter<String, List<FlowRule>> converter = source -> JSON.parseArray(source, FlowRule.class);
        return new NacosDataSource<>(sharedProperties(), groupId, dataId, converter);
    }

    /**
     * 熔断规则数据源
     */
    @Bean
    @Lazy(false)
    public ReadableDataSource<String, List<DegradeRule>> degradeRuleDataSource(
            @Value("${sentinel.datasource.degrade.nacos.data-id}") String dataId) {
        log.info("=== 创建 degradeRuleDataSource, dataId: {} ===", dataId);
        Converter<String, List<DegradeRule>> converter = source -> JSON.parseArray(source, DegradeRule.class);
        return new NacosDataSource<>(sharedProperties(), groupId, dataId, converter);
    }

    /**
     * 热点参数限流规则数据源
     */
    @Bean
    @Lazy(false)
    public ReadableDataSource<String, List<ParamFlowRule>> paramFlowRuleDataSource(
            @Value("${sentinel.datasource.param-flow.nacos.data-id}") String dataId) {
        log.info("=== 创建 paramFlowRuleDataSource, dataId: {} ===", dataId);
        Converter<String, List<ParamFlowRule>> converter = source -> JSON.parseArray(source, ParamFlowRule.class);
        return new NacosDataSource<>(sharedProperties(), groupId, dataId, converter);
    }

    /**
     * 系统规则数据源
     */
    @Bean
    @Lazy(false)
    public ReadableDataSource<String, List<SystemRule>> systemRuleDataSource(
            @Value("${sentinel.datasource.system.nacos.data-id}") String dataId) {
        log.info("=== 创建 systemRuleDataSource, dataId: {} ===", dataId);
        Converter<String, List<SystemRule>> converter = source -> JSON.parseArray(source, SystemRule.class);
        return new NacosDataSource<>(sharedProperties(), groupId, dataId, converter);
    }

    /**
     * 授权规则数据源
     */
    @Bean
    @Lazy(false)
    public ReadableDataSource<String, List<AuthorityRule>> authorityRuleDataSource(
            @Value("${sentinel.datasource.authority.nacos.data-id}") String dataId) {
        log.info("=== 创建 authorityRuleDataSource, dataId: {} ===", dataId);
        Converter<String, List<AuthorityRule>> converter = source -> JSON.parseArray(source, AuthorityRule.class);
        return new NacosDataSource<>(sharedProperties(), groupId, dataId, converter);
    }

    /**
     * 全部单例 bean 就绪后，将各规则数据源注册到 Sentinel RuleManager。
     * Sentinel 的 SPI InitFunc 机制无法感知 Spring 容器中的 bean，
     * 因此通过 SmartInitializingSingleton 生命周期回调完成注册。
     */
    @Override
    public void afterSingletonsInstantiated() {
        log.info("=== Sentinel 数据源注册开始 ===");
        shareConfigServiceAcross(flowRuleDataSource.getObject(), degradeRuleDataSource.getObject(),
                paramFlowRuleDataSource.getObject(), systemRuleDataSource.getObject(),
                authorityRuleDataSource.getObject());
        FlowRuleManager.register2Property(flowRuleDataSource.getObject().getProperty());
        DegradeRuleManager.register2Property(degradeRuleDataSource.getObject().getProperty());
        ParamFlowRuleManager.register2Property(paramFlowRuleDataSource.getObject().getProperty());
        SystemRuleManager.register2Property(systemRuleDataSource.getObject().getProperty());
        AuthorityRuleManager.register2Property(authorityRuleDataSource.getObject().getProperty());

        // 显式启动 Sentinel 初始化（SPI InitFunc: CommandCenter + HeartbeatSender），
        // 使网关启动后立即向 Dashboard 上报心跳，无需等待首个请求触发
        InitExecutor.doInit();
        log.info("=== Sentinel 数据源注册完成 ===");
    }
}
