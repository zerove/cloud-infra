package com.cloudinfra.system.grpc;

import com.cloudinfra.framework.grpc.EnableGrpcServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** 只在隔离开发环境显式启用业务适配层时导入服务器。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cloudinfra.system.grpc", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableGrpcServer
public class SystemGrpcConfiguration {
}
