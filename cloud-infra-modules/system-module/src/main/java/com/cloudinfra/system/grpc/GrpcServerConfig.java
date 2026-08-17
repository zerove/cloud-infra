package com.cloudinfra.system.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * gRPC Server 配置
 * 使用原生 io.grpc NettyServerBuilder 启动 gRPC 服务
 * 替代不存在的 spring-boot-starter-grpc
 * 
 * 测试环境下自动禁用（test profile）
 */
@Slf4j
@Configuration
@Profile("!test")  // 测试环境不启动 gRPC 服务器
public class GrpcServerConfig {

    @Value("${grpc.server.port:9090}")
    private int port;

    private Server grpcServer;

    private final List<io.grpc.BindableService> services;

    public GrpcServerConfig(List<io.grpc.BindableService> services) {
        this.services = services;
    }

    @PostConstruct
    public void start() throws Exception {
        NettyServerBuilder builder = NettyServerBuilder
            .forPort(port)
            .maxInboundMessageSize(10 * 1024 * 1024)   // 10MB
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveTime(10, TimeUnit.SECONDS);

        for (io.grpc.BindableService service : services) {
            builder.addService(service);
            log.info("gRPC 服务注册: {}", service.bindService().getServiceDescriptor().getName());
        }

        grpcServer = builder.build().start();
        log.info("gRPC Server 启动成功，监听端口: {}", port);
    }

    @PreDestroy
    public void stop() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            log.info("gRPC Server 已关闭");
        }
    }
}