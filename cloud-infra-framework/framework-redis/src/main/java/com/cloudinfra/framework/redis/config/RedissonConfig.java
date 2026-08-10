package com.cloudinfra.framework.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@AutoConfiguration
public class RedissonConfig {

    @Value("${spring.data.redis.host:redis}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.redisson.client.single-server.connection-pool-size:8}")
    private int connectionPoolSize;

    @Value("${spring.data.redis.redisson.client.single-server.connection-minimum-idle-size:2}")
    private int connectionMinimumIdleSize;

    @Value("${spring.data.redis.redisson.client.single-server.idle-connection-timeout:10000}")
    private int idleConnectionTimeout;

    @Value("${spring.data.redis.redisson.client.single-server.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${spring.data.redis.redisson.client.single-server.timeout:5000}")
    private int timeout;

    @Value("${spring.data.redis.redisson.client.single-server.retry-attempts:3}")
    private int retryAttempts;

    @Value("${spring.data.redis.redisson.client.single-server.retry-interval:1000}")
    private int retryInterval;

    @Value("${spring.data.redis.redisson.client.single-server.subscriptions-per-connection:5}")
    private int subscriptionsPerConnection;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
            host,
            port
        );
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }
        config.setDatabase(database);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
        RedisConnectionFactory connectionFactory
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Spring Boot 4 仅提供 Jackson 3 (tools.jackson) 的 ObjectMapper bean，
        // 而本配置使用 Jackson 2 (com.fasterxml.jackson) 序列化器，故内部创建实例
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL
        );

        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);


        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = String.format("redis://%s:%d", host, port);
        config.useSingleServer()
            .setAddress(address)
            .setDatabase(database)
            .setConnectionPoolSize(connectionPoolSize)
            .setConnectionMinimumIdleSize(connectionMinimumIdleSize)
            .setIdleConnectionTimeout(idleConnectionTimeout)
            .setConnectTimeout(connectTimeout)
            .setTimeout(timeout)
            .setRetryAttempts(retryAttempts)
            .setRetryInterval(retryInterval)
            .setSubscriptionsPerConnection(subscriptionsPerConnection);

        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }
}
