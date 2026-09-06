package com.cloudinfra.framework.grpc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 * <p>依赖: grpc-spring-boot-starter (LogNet) 提供 gRPC server 核心能力.</p>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(CloudInfraGrpcProperties.class)
public class GrpcServerAutoConfiguration {

}
