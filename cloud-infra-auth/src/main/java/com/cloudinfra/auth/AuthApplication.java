package com.cloudinfra.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;


@EnableFeignClients(basePackages = {"com.cloudinfra.auth.feign", "com.cloudinfra.api.system"})
@EnableDiscoveryClient
@SpringBootApplication(
    scanBasePackages = {"com.cloudinfra"},
    exclude = {
        // RefreshScope 提供者：禁用后 resilience4j 的 @ConditionalOnBean(RefreshScope)
        // 配置全部不满足，避免 AOT (process-aot) 代码生成失败
        // （UnsupportedTypeValueCodeGenerationException: RefreshScope）
        RefreshAutoConfiguration.class
    }
)
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
