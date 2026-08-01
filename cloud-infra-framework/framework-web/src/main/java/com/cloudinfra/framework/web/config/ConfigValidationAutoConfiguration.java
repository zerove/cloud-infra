package com.cloudinfra.framework.web.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 配置校验自动配置
 * 在应用启动时校验关键配置是否存在
 */
@AutoConfiguration
@EnableConfigurationProperties(ConfigValidationProperties.class)
public class ConfigValidationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidationAutoConfiguration.class);

    private final ConfigValidationProperties properties;

    public ConfigValidationAutoConfiguration(ConfigValidationProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        if (!properties.isEnabled()) {
            log.info("配置校验已禁用");
            return;
        }

        List<String> missingConfigs = properties.getRequiredConfigs().stream()
                .filter(key -> System.getProperty(key) == null && System.getenv(key) == null)
                .collect(Collectors.toList());

        if (!missingConfigs.isEmpty()) {
            String message = "缺少必需的配置项: " + String.join(", ", missingConfigs)
                    + "。请通过环境变量或 JVM 参数设置。";
            if (properties.isFailFast()) {
                throw new IllegalStateException(message);
            } else {
                log.warn(message);
            }
        } else {
            log.info("配置校验通过，所有必需配置项均已设置");
        }

        // 记录当前生效的 Nacos 配置（脱敏）
        logNacosConfig();
    }

    private void logNacosConfig() {
        String configAddr = System.getProperty("NACOS_CONFIG_ADDR") != null
                ? System.getProperty("NACOS_CONFIG_ADDR")
                : System.getenv("NACOS_CONFIG_ADDR");
        String discoveryAddr = System.getProperty("NACOS_DISCOVERY_ADDR") != null
                ? System.getProperty("NACOS_DISCOVERY_ADDR")
                : System.getenv("NACOS_DISCOVERY_ADDR");
        String nacosAddr = System.getProperty("NACOS_ADDR") != null
                ? System.getProperty("NACOS_ADDR")
                : System.getenv("NACOS_ADDR");

        log.info("Nacos 配置地址: config={}, discovery={}, fallback={}",
                mask(configAddr), mask(discoveryAddr), mask(nacosAddr));
    }

    private String mask(String value) {
        if (value == null) return "未设置";
        if (value.contains(":")) {
            String[] parts = value.split(":");
            return parts[0] + ":***";
        }
        return "***";
    }
}

@Configuration
@EnableConfigurationProperties(ConfigValidationProperties.class)
class ConfigValidationProperties {

    /**
     * 是否启用配置校验
     */
    private boolean enabled = true;

    /**
     * 校验失败时是否快速失败
     */
    private boolean failFast = false;

    /**
     * 必需的配置项（环境变量或 JVM 参数）
     */
    private List<String> requiredConfigs = Arrays.asList(
            "NACOS_ADDR",
            "SPRING_DATASOURCE_HOST",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "AUTH_JWT_SECRET"
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public List<String> getRequiredConfigs() {
        return requiredConfigs;
    }

    public void setRequiredConfigs(List<String> requiredConfigs) {
        this.requiredConfigs = requiredConfigs;
    }
}