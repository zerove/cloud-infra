package com.cloudinfra.framework.grpc;

import io.grpc.BindableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * gRPC Server 自动配置.
 *
 * <p>由 {@link EnableGrpcServer} 注解 {@code @Import} 生效.
 * 自动注册 gRPC server 相关 bean, 业务模块无需手动配置.
 *
 * <p>核心能力:
 * <ul>
 *   <li>配置 gRPC server 端口 (默认 9090), keep-alive, SSL 等</li>
 *   <li>自动发现并注册所有 {@link BindableService} bean (即实现了 gRPC 服务的实现类)</li>
 *   <li>实现 {@link org.springframework.context.SmartLifecycle} 生命周期管理</li>
 *   <li>与 Spring Boot 4 虚拟线程兼容</li>
 *   <li>支持进程内模式 (集成测试)</li>
 * </ul>
 *
 * <p>依赖: 仅需 io.grpc:grpc-netty-shaded, grpc-protobuf, grpc-stub, grpc-services,
 * 无第三方 starter 依赖, 完全兼容 Spring Boot 4.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "cloudinfra.grpc", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CloudInfraGrpcProperties.class)
@Import(GrpcServerLifecycle.class)
public class GrpcServerAutoConfiguration {

    /**
     * 收集所有 BindableService 实现类.
     * Spring 容器会自动注入所有实现了 BindableService 的 bean (即 @GRpcService 或手动实现 bindService() 的类).
     */
    @Bean
    @ConditionalOnMissingBean
    public List<BindableService> grpcBindableServices(List<BindableService> services) {
        log.info("Discovered {} gRPC BindableService bean(s)", services.size());
        services.forEach(s -> log.debug("  - {}", s.bindService().getServiceDescriptor().getName()));
        return services;
    }
}