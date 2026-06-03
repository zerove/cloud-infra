package com.cloudinfra.gateway;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.cloudinfra.common.util.JwtUtils;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

@EnableDiscoveryClient
@SpringBootApplication(
    scanBasePackages = { "com.cloudinfra.gateway", "com.cloudinfra.common" }
)
@Import({RedissonConfig.class, TracingAutoConfiguration.class, PasswordEncoderConfig.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public JwtUtils jwtUtils() {
        System.out.println(">>> [GatewayApplication] Manually creating JwtUtils bean...");
        return new JwtUtils();
    }
}
