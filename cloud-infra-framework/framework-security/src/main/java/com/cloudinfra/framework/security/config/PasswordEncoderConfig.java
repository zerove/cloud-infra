package com.cloudinfra.framework.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@AutoConfiguration
public class PasswordEncoderConfig {
    // 缺此条件时, 消费方自带的 PasswordEncoder (如 auth 的 AuthSecurityConfig)
    // 会与之撞名并抛 BeanDefinitionOverrideException。2026-10-17 由 IT 跑出。
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
