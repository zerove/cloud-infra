package com.cloudinfra.framework.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Jackson 2 兼容配置
 *
 * Spring Boot 4 默认启用 Jackson 3 (tools.jackson)，容器中不再自动提供
 * com.fasterxml.jackson.databind.ObjectMapper (Jackson 2) bean。
 * 平台组件 (如 StandardResponseAdvice) 及第三方库仍基于 Jackson 2，
 * 这里按需补充一个 Jackson 2 ObjectMapper bean，互不影响 Jackson 3 的
 * HTTP 消息转换。
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class Jackson2CompatibilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
