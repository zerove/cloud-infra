package com.cloudinfra.gateway.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式定义 Servlet WebServer Factory Bean。
 * 
 * 在 Spring Boot 4.1.0 中，Web Server 自动配置被拆分为多个 jar（spring-boot-tomcat,
 * spring-boot-web-server），这些 auto-configuration 基于 @ConditionalOnClass 注解。
 * 在 Native Image 的 AOT 处理过程中，这些条件可能会导致 bean 定义被过滤，
 * 使得 ServletWebServerFactory bean 无法注册，从而出现
 * "no org.springframework.boot.web.server.servlet.ServletWebServerFactory bean defined" 错误。
 * 
 * 通过在应用层直接声明 @Bean，可以绕过 AOT 的条件过滤，确保 bean 定义被正确生成。
 */
@Configuration
public class WebServerConfig {

    @Bean
    public ServletWebServerFactory servletWebServerFactory() {
        return new TomcatServletWebServerFactory();
    }
}
