package com.cloudinfra.framework.grpc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloud-Infra gRPC 配置属性.
 *
 * <p>前缀: {@code cloudinfra.grpc}
 * <pre>
 * cloudinfra:
 *   grpc:
 *     port: 9090
 *     in-process-server-name: cloud-infra-grpc
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "cloudinfra.grpc")
public class CloudInfraGrpcProperties {

    /** gRPC server 端口. 默认 9090 (与 REST 8080 分离). */
    private Integer port = 9090;

    /** 进程内 gRPC server 名称 (用于本地测试). */
    private String inProcessServerName;

    /** 是否启用 gRPC server. 默认 true. */
    private boolean enabled = true;
}
