package com.cloudinfra.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Component;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.ConfigurableListableBeanFactory;
import org.springframework.beans.BeansException;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.cloudinfra.gateway", "com.cloudinfra.framework"}, excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
    @Component
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            if (System.getProperty("spring.aot.processing") != null) {
                if (registry.containsBeanDefinition("gatewayRouterFunctionHolder")) {
                    registry.removeBeanDefinition("gatewayRouterFunctionHolder");
                }
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        }
    }
}
