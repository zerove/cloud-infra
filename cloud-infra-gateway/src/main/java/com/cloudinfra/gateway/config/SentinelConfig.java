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
     * 限流规则数据源
     */
    @Bean
    @Lazy(false)
    public ReadableDataSource<String, List<FlowRule>> flowRuleDataSource(
            @Value("${sentinel.datasource.flow.nacos.data-id}") String dataId) {
        log.info("=== 创建 flowRuleDataSource, dataId: {} ===", dataId);
        Converter<String, List<FlowRule>> converter = source -> JSON.parseArray(source, FlowRule.class);
        return new NacosDataSource<>(serverAddr, groupId, dataId, converter);
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
        return new NacosDataSource<>(serverAddr, groupId, dataId, converter);
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
        return new NacosDataSource<>(serverAddr, groupId, dataId, converter);
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
        return new NacosDataSource<>(serverAddr, groupId, dataId, converter);
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
        return new NacosDataSource<>(serverAddr, groupId, dataId, converter);
    }

    /**
     * 全部单例 bean 就绪后，将各规则数据源注册到 Sentinel RuleManager。
     * Sentinel 的 SPI InitFunc 机制无法感知 Spring 容器中的 bean，
     * 因此通过 SmartInitializingSingleton 生命周期回调完成注册。
     */
    @Override
    public void afterSingletonsInstantiated() {
        log.info("=== Sentinel 数据源注册开始 ===");
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
