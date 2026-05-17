package com.cloudinfra.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@Configuration
@EnableRedisRepositories(basePackages = "com.cloudinfra.notification.repository.redis")
public class RedisConfig {
}
