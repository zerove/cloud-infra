package com.cloudinfra.framework.web.idempotency;

import com.cloudinfra.test.support.containers.InfrastructureContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdempotencyStore 集成测试 (M6-02 Phase B)
 *
 * 真实 Redis 容器 (testcontainers), 覆盖 5 个端到端路径:
 * - SET NX EX 锁机制 (Redis 原子)
 * - status 状态机 IN_FLIGHT → COMPLETED
 * - response 实际序列化 + 反序列化
 * - releaseLock 后可重新获得锁
 * - 多 key 隔离
 */
@SpringBootTest(classes = IdempotencyStoreIT.RedisTestConfig.class)
class IdempotencyStoreIT {

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private String testKeyHash;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        GenericContainer<?> redis = InfrastructureContainers.REDIS;
        redis.start();
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        testKeyHash = "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(IdempotencyEntry.lockKey(testKeyHash));
        redisTemplate.delete(IdempotencyEntry.statusKey(testKeyHash));
        redisTemplate.delete(IdempotencyEntry.responseKey(testKeyHash));
    }

    @Test
    @DisplayName("tryAcquire: Redis SET NX EX 原子锁, 二次调用返回 false")
    void shouldAcquireAndRejectDuplicate() {
        boolean first = store.tryAcquire(testKeyHash, 60);
        boolean second = store.tryAcquire(testKeyHash, 60);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(store.isInFlight(testKeyHash)).isTrue();
    }

    @Test
    @DisplayName("storeResponse + read: 端到端 JSON 序列化往返")
    void shouldStoreAndReadResponse() {
        IdempotencyEntry entry = IdempotencyEntry.success(200, "{\"token\":\"xyz789\"}");
        store.storeResponse(testKeyHash, entry, 60);

        IdempotencyEntry read = store.read(testKeyHash);

        assertThat(read).isNotNull();
        assertThat(read.isSuccess()).isTrue();
        assertThat(read.getStatusCode()).isEqualTo(200);
        assertThat(read.getBody()).isEqualTo("{\"token\":\"xyz789\"}");
    }

    @Test
    @DisplayName("read: status=IN_FLIGHT 时返回 null (业务执行中)")
    void shouldReturnNullDuringInFlight() {
        store.tryAcquire(testKeyHash, 60);

        IdempotencyEntry read = store.read(testKeyHash);

        assertThat(read).isNull();
    }

    @Test
    @DisplayName("releaseLock: 释放后 tryAcquire 重新成功")
    void shouldAllowReacquireAfterRelease() {
        store.tryAcquire(testKeyHash, 60);
        store.releaseLock(testKeyHash);

        boolean reacquired = store.tryAcquire(testKeyHash, 60);

        assertThat(reacquired).isTrue();
    }

    @Test
    @DisplayName("多 key 隔离: 不同 hash 互不影响")
    void shouldIsolateDifferentKeys() {
        String keyA = "test-key-A-" + UUID.randomUUID().toString().substring(0, 8);
        String keyB = "test-key-B-" + UUID.randomUUID().toString().substring(0, 8);

        boolean acquiredA = store.tryAcquire(keyA, 60);
        boolean acquiredB = store.tryAcquire(keyB, 60);

        assertThat(acquiredA).isTrue();
        assertThat(acquiredB).isTrue();

        redisTemplate.delete(IdempotencyEntry.lockKey(keyA));
        redisTemplate.delete(IdempotencyEntry.statusKey(keyA));
        redisTemplate.delete(IdempotencyEntry.lockKey(keyB));
        redisTemplate.delete(IdempotencyEntry.statusKey(keyB));
    }

    /**
     * 最小 Spring 配置: RedisTemplate + IdempotencyStore (手动配置避免与 RedissonConfig 冲突)
     */
    @Configuration
    static class RedisTestConfig {

        @Bean(destroyMethod = "destroy")
        public RedisConnectionFactory redisConnectionFactory(
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host}") String host,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port}") int port
        ) {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
            RedisTemplate<String, Object> template = new RedisTemplate<>();
            template.setConnectionFactory(cf);
            template.setKeySerializer(new StringRedisSerializer());
            template.setHashKeySerializer(new StringRedisSerializer());
            // 启用 default typing, JSON 包含 @class 信息以便反序列化
            ObjectMapper mapper = new ObjectMapper();
            mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
            );
            template.setValueSerializer(new GenericJackson2JsonRedisSerializer(mapper));
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        public IdempotencyStore idempotencyStore(RedisTemplate<String, Object> redisTemplate) {
            return new IdempotencyStore(redisTemplate, new ObjectMapper());
        }
    }
}