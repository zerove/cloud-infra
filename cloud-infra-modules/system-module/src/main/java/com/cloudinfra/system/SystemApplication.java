package com.cloudinfra.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

@EnableDiscoveryClient
@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.cloudinfra.api")
@EnableTransactionManagement
@SpringBootApplication(
    scanBasePackages = {"com.cloudinfra"},
    exclude = {
        io.seata.spring.boot.autoconfigure.SeataSpringFenceAutoConfiguration.class
    }
)
@Import({RedissonConfig.class, TracingAutoConfiguration.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class SystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
