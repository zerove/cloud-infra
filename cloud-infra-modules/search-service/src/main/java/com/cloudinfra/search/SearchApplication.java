package com.cloudinfra.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * 搜索服务启动类
 */
@EnableDiscoveryClient
@EnableElasticsearchRepositories(basePackages = "com.cloudinfra.search.repository")
@SpringBootApplication(scanBasePackages = {"com.cloudinfra.search", "com.cloudinfra.framework", "com.cloudinfra.search.repository"})
public class SearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
