package com.cloudinfra.framework.grpc;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用 gRPC Server 功能.
 *
 * <p>业务模块在主类上添加此注解, 即开启 gRPC server 暴露业务服务:
 * <pre>
 * &#64;SpringBootApplication
 * &#64;EnableFeignClients
 * &#64;EnableGrpcServer  // 启用 gRPC server
 * public class SystemApplication { }
 * </pre>
 *
 * <p>等效于导入 {@link GrpcServerAutoConfiguration}.</p>
 *
 * @see GrpcServerAutoConfiguration
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Import(GrpcServerAutoConfiguration.class)
public @interface EnableGrpcServer {
}
