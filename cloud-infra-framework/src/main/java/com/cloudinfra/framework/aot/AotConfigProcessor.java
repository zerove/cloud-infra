package com.cloudinfra.framework.aot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 在 AOT 构建期静默 Nacos 相关强制检查
 */
public class AotConfigProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (Boolean.TRUE.toString().equalsIgnoreCase(environment.getProperty("spring.aot.enabled"))) {
            Map<String, Object> properties = new HashMap<>();
            // 静默 Nacos 配置检查
            properties.put("spring.cloud.nacos.config.import-check.enabled", "false");
            
            environment.getPropertySources().addFirst(new MapPropertySource("aot-nacos-silencer", properties));
        }
    }
}
