package com.cloudinfra.grpc.lib;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EchoService gRPC 实现.
 *
 * <p>用于验证 gRPC 基础设施是否正常工作.
 *
 * <p>使用 {@link Component} 注解让 Spring 自动注册为 Bean,
 * 原生自动配置会自动发现所有实现 {@link io.grpc.BindableService} 的 bean.
 */
@Slf4j
@Component
public class EchoServiceImpl extends EchoServiceGrpc.EchoServiceImplBase {

    private static final String SERVER_VERSION = "1.0.0";

    @Override
    public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
        String message = request.getMessage();
        int count = request.getCount();

        String repeated = (count > 0) 
            ? message.repeat(Math.min(count, 10))  // 安全上限
            : message;

        EchoResponse response = EchoResponse.newBuilder()
            .setMessage(repeated)
            .setTimestamp(System.currentTimeMillis())
            .setServerVersion(SERVER_VERSION)
            .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        log.debug("Echo request: message={}, count={}", message, count);
    }

    @Override
    public void echoWithMetadata(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
        // TODO: 从 metadata 提取租户/用户上下文
        echo(request, responseObserver);
    }

    @Override
    public void health(HealthRequest request, StreamObserver<HealthResponse> responseObserver) {
        HealthResponse response = HealthResponse.newBuilder()
            .setStatus(HealthResponse.ServingStatus.SERVING)
            .setVersion(SERVER_VERSION)
            .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
