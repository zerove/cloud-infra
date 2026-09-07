package com.cloudinfra.framework.grpc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * gRPC Server 自动配置.
 *
 * <p>由 {@link EnableGrpcServer} 注解 {@code @Import} 生效.
 * 自动注册 gRPC server 相关 bean, 业务模块无需手动配置.</p>
 *
 * <p>核心能力:
 * <ul>
 *   <li>配置 gRPC server 端口 (默认 9090)</li>
 *   <li>与 Spring Boot 4 虚拟线程兼容</li>
 * </ul>
 *
 * <p>依赖: 业务模块需显式引入 io.github.lognet:grpc-spring-boot-starter (optional).
 * LogNet 3.0.1/5.2.0 均基于 Spring Boot 3.x, 与 Spring Boot 4 集成存在
 * EnvironmentPostProcessor 兼容问题. 详见 ROADMAP.md M7-02 Phase C.</p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = "org.lognet.springboot.grpc.autoconfigure.GRpcAutoConfiguration")
@EnableConfigurationProperties(CloudInfraGrpcProperties.class)
public class GrpcServerAutoConfiguration {

}
