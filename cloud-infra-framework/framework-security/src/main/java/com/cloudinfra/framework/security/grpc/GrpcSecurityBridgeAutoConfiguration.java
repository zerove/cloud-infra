package com.cloudinfra.framework.security.grpc;

import com.cloudinfra.common.core.security.CallerContextBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * gRPC 调用安全边界的 Spring Security 桥接装配 (M7-02).
 *
 * <p>必须在 {@code GrpcServerAutoConfiguration} 之前注册,
 * 以便覆盖 framework-grpc 提供的 no-op 缺省实现。
 *
 * <p>仅当 classpath 上存在 framework-grpc 时生效, 因此单用 framework-security
 * 的服务 (gateway / auth 等) 不受影响。
 */
@AutoConfiguration(beforeName = "com.cloudinfra.framework.grpc.GrpcServerAutoConfiguration")
@ConditionalOnClass(name = "com.cloudinfra.framework.grpc.GrpcSecurityInterceptor")
public class GrpcSecurityBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CallerContextBinder springSecurityCallerContextBinder() {
        return new SpringSecurityCallerContextBinder();
    }
}
