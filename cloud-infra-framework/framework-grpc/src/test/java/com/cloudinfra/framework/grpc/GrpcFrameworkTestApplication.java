package com.cloudinfra.framework.grpc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * gRPC 框架集成测试启动类.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"com.cloudinfra.framework.grpc", "com.cloudinfra.grpc.lib"},
    useDefaultFilters = false,
    includeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = org.springframework.stereotype.Component.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = org.springframework.stereotype.Service.class)
    }
)
public class GrpcFrameworkTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(GrpcFrameworkTestApplication.class, args);
    }
}
