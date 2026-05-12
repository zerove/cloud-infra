package com.cloudinfra.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 搜索服务启动类
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.cloudinfra.search", "com.cloudinfra.framework"})
public class SearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
