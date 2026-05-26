package com.cloudinfra.gateway;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.stereotype.Component;

@EnableDiscoveryClient
@SpringBootApplication(
    scanBasePackages = { "com.cloudinfra.gateway", "com.cloudinfra.framework" }
)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Component
    public static class GatewayAotProcessor
        implements BeanDefinitionRegistryPostProcessor
    {

        @Override
        public void postProcessBeanDefinitionRegistry(
            BeanDefinitionRegistry registry
        ) throws BeansException {
            if (System.getProperty("spring.aot.processing") != null) {
                if (
                    registry.containsBeanDefinition(
                        "gatewayRouterFunctionHolder"
                    )
                ) {
                    registry.removeBeanDefinition(
                        "gatewayRouterFunctionHolder"
                    );
                }
            }
        }

        @Override
        public void postProcessBeanFactory(
            ConfigurableListableBeanFactory beanFactory
        ) throws BeansException {}
    }
}
