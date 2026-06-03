package com.cloudinfra.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;
import com.cloudinfra.framework.security.config.SecurityAutoConfiguration;
import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.framework.security.provider.RemotePermissionProvider;
import java.util.Collections;
import java.util.List;

@EnableFeignClients(basePackages = {"com.cloudinfra.auth.feign", "com.cloudinfra.api.system"})
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.cloudinfra"})
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
