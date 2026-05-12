package com.cloudinfra.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.cloudinfra.gateway", "com.cloudinfra.framework"}, excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
