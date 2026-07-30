package com.cloudinfra.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * 搜索服务启动类
 */
import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

@EnableDiscoveryClient
@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.cloudinfra.api")
@EnableElasticsearchRepositories(basePackages = "com.cloudinfra.search.repository")
@SpringBootApplication(scanBasePackages = {"com.cloudinfra"})
@Import({RedissonConfig.class, TracingAutoConfiguration.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class SearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
