package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationDispatchMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.cloudinfra.test.support.containers.InfrastructureContainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知分发补扫的多副本竞争验证 (issue 27 决策 2 遗留项)
 *
 * <p>单元测试用 mock 验证了锁语义 (不误删他人锁), 但**真实多实例同时扫描**从未验证。
 * 本 IT 起两个独立的 {@code NotificationDispatchRecoveryTask} 实例 (等价于两个副本),
 * 共享同一套真实 PostgreSQL + Redis + RabbitMQ, 验证:
 *
 * <ol>
 *   <li>并发扫描时, Redis 分布式锁保证**只有一个**副本真正执行扫描</li>
 *   <li>未拿到锁的副本既不扫描也不删锁, 且不影响持锁副本的流程</li>
 *   <li>持锁副本完成后正常释放锁 (值匹配才删)</li>
 *   <li>串行补扫会重复重投 (at-least-once 的预期), 去重责任在消费者 CAS —— 本 IT
 *       显式断言这一职责边界, 避免后续误加分布式去重表</li>
 * </ol>
 */
@Testcontainers
@SpringBootTest(classes = NotificationRecoveryMultiReplicaIT.MultiReplicaTestApplication.class)
class NotificationRecoveryMultiReplicaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("cloud_infra")
        .withUsername("postgres")
        .withPassword("password")
        // 受控对照(2026-10-07): 本机同时起 3 个 PostgreSQL 需 **157 秒**,
        // 单个只需 10-38 秒。Testcontainers 默认 60 秒, 在多个 IT 同轮时
        // 必然超时, 且报错会在容器间**随机漂移**(同一 IT 有时报 PG、
        // 有时报 RabbitMQ) —— 资源竞争的签名, 不是某个容器配错了。
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .waitingFor(org.testcontainers.containers.wait.strategy.Wait
            .forLogMessage(".*Ready to accept connections.*", 1))
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-alpine")
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @Configuration(proxyBeanMethods = false)
    static class MultiReplicaTestApplication {

        @Bean
        DataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(postgres.getJdbcUrl());
            cfg.setUsername(postgres.getUsername());
            cfg.setPassword(postgres.getPassword());
            cfg.setMaximumPoolSize(10);
            return new HikariDataSource(cfg);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        CachingConnectionFactory rabbitConnectionFactory() {
            var factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
            factory.setUsername(rabbit.getAdminUsername());
            factory.setPassword(rabbit.getAdminPassword());
            return factory;
        }

        @Bean
        RabbitAdmin rabbitAdmin(CachingConnectionFactory cf) {
            return new RabbitAdmin(cf);
        }

        @Bean
        RabbitTemplate rabbitTemplate(CachingConnectionFactory cf) {
            return new RabbitTemplate(cf);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            var factory = new org.springframework.data.redis.connection.lettuce
                .LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            factory.afterPropertiesSet();
            return new StringRedisTemplate(factory);
        }

        @Bean
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource ds)
                throws Exception {
            var factoryBean = new com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(ds);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setEnvironment(new org.apache.ibatis.mapping.Environment(
                "it", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds));
            cfg.addMapper(NotificationDispatchMapper.class);
            cfg.addMapper(com.cloudinfra.notification.mapper.NotificationLogMapper.class);
            factoryBean.setConfiguration(cfg);
            return factoryBean.getObject();
        }

        @Bean
        NotificationDispatchMapper notificationDispatchMapper(
                org.apache.ibatis.session.SqlSessionFactory factory) {
            return new org.mybatis.spring.SqlSessionTemplate(factory)
                .getMapper(NotificationDispatchMapper.class);
        }
    }

    private static final String DDL = """
        CREATE TABLE IF NOT EXISTS sys_notification (
            id                BIGSERIAL PRIMARY KEY,
            user_id           BIGINT       NOT NULL,
            type              VARCHAR(32)  NOT NULL,
            title             VARCHAR(255) NOT NULL,
            content           TEXT         NOT NULL,
            status            SMALLINT     NOT NULL DEFAULT 0,
            create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            read_time         TIMESTAMP,
            dispatch_status   SMALLINT     NOT NULL DEFAULT 0,
            dispatch_attempts SMALLINT     NOT NULL DEFAULT 0
        )
        """;

    @Autowired private NotificationDispatchMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory;

    /** 两个"副本": 共享全部外部状态, 但各自独立 —— 等价于同进程内的两实例。 */
    private NotificationDispatchRecoveryTask replicaA;
    private NotificationDispatchRecoveryTask replicaB;

    @BeforeEach
    void setUp() {
        jdbc.execute(DDL);
        jdbc.update("DELETE FROM sys_notification");
        redisTemplate.delete("notification:dispatch:recovery:lock");
        rabbitAdmin.deleteQueue("notification.dispatch.queue");
        rabbitAdmin.declareQueue(
            org.springframework.amqp.core.QueueBuilder.durable(
                "notification.dispatch.queue").build());

        // 补扫任务只用到 dispatcher.exhausted() —— 纯函数, 只读实体的 dispatchAttempts。
        // 渠道路由相关协作者在该路径上不会被触及, 用 mock 避免拉起整套渠道装配。
        NotificationDispatcher dispatcher = new NotificationDispatcher(
            mapper,
            new org.mybatis.spring.SqlSessionTemplate(sqlSessionFactory)
                .getMapper(com.cloudinfra.notification.mapper.NotificationLogMapper.class),
            org.mockito.Mockito.mock(NotificationRouter.class),
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());

        replicaA = new NotificationDispatchRecoveryTask(
            mapper, rabbitTemplate, redisTemplate, dispatcher,
            new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run));
        replicaB = new NotificationDispatchRecoveryTask(
            mapper, rabbitTemplate, redisTemplate, dispatcher,
            new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run));
    }

    @Test
    @DisplayName("并发扫描: 分布式锁保证只有一个副本执行")
    void concurrentScanIsGuardedByDistributedLock() throws Exception {
        for (int i = 1; i <= 3; i++) {
            insert(0, 0, LocalDateTime.now().minusHours(1));
        }

        // 两副本真正并发起跑, 抢同一把 Redis 锁
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (NotificationDispatchRecoveryTask replica : List.of(replicaA, replicaB)) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                    replica.recoverStaleDispatches();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // 核心断言: 只有持锁的那个副本执行了扫描, 3 条通知各重投一次, 不重复
        assertThat(drainQueue()).as("同一批陈旧通知只应被重投一次").isEqualTo(3);
        assertThat(redisTemplate.hasKey("notification:dispatch:recovery:lock"))
            .as("扫描结束后锁应已释放").isFalse();
    }

    @Test
    @DisplayName("锁被他人持有: 后到副本跳过扫描且不删锁")
    void secondReplicaSkipsAndKeepsForeignLock() {
        // 模拟另一个副本正持有锁 (值不同)
        String foreignToken = "held-by-another-replica";
        redisTemplate.opsForValue().set(
            "notification:dispatch:recovery:lock", foreignToken, java.time.Duration.ofSeconds(60));
        insert(0, 0, LocalDateTime.now().minusHours(1));

        replicaA.recoverStaleDispatches();

        assertThat(drainQueue()).as("未持锁的副本不应重投任何消息").isZero();
        // 关键: 不得删除他人持有的锁
        assertThat(redisTemplate.opsForValue().get("notification:dispatch:recovery:lock"))
            .as("不得误删他人锁").isEqualTo(foreignToken);
    }

    @Test
    @DisplayName("串行补扫: 重复重投是 at-least-once 的预期, 去重责任在消费者 CAS")
    void sequentialRepublishIsExpectedAndDedupedByConsumerCas() {
        insert(0, 0, LocalDateTime.now().minusHours(1));
        insert(0, 0, LocalDateTime.now().minusHours(1));

        replicaA.recoverStaleDispatches();
        assertThat(redisTemplate.hasKey("notification:dispatch:recovery:lock"))
            .as("A 完成后应释放锁").isFalse();

        replicaB.recoverStaleDispatches();

        // 无消费者介入时, 两副本各自重投一遍 —— 这是 at-least-once 的预期行为:
        // 补扫的职责是"保证不丢", 而非"保证不重"; 去重由消费者侧 CAS 抢占承担
        // (见下方 onlyOneReplicaClaimsTheSameRow)。刻意在此断言这一职责边界,
        // 避免后续误以为补扫需要自行去重而引入分布式去重表。
        assertThat(drainQueue()).as("两副本各重投一次, 共 4 条").isEqualTo(4);
    }

    @Test
    @DisplayName("两副本竞争同一行: 只有一个能 CAS 抢占成功 (补扫与消费者交叉)")
    void onlyOneReplicaClaimsTheSameRow() {
        Long id = insert(0, 0, LocalDateTime.now().minusHours(1));

        // 模拟: 副本 A 的补扫重投被消费者取走并抢占; 副本 B 稍后也重投同一条
        int claimedByA = mapper.claimDispatch(id, DispatchStatus.PENDING);
        int claimedByB = mapper.claimDispatch(id, DispatchStatus.PENDING);

        assertThat(claimedByA).isEqualTo(1);
        assertThat(claimedByB).as("第二次 CAS 必须失败, 否则用户会收到重复通知").isZero();
        assertThat(attemptsOf(id)).isEqualTo(1);
    }

    // ========== 辅助 ==========

    private Long insert(int dispatchStatus, int attempts, LocalDateTime createTime) {
        return jdbc.queryForObject(
            "INSERT INTO sys_notification (user_id, type, title, content, status, "
                + "dispatch_status, dispatch_attempts, create_time) "
                + "VALUES (1, 'EMAIL', 't', 'c', 0, ?, ?, ?) RETURNING id",
            Long.class, dispatchStatus, attempts, createTime);
    }

    private int attemptsOf(Long id) {
        return jdbc.queryForObject(
            "SELECT dispatch_attempts FROM sys_notification WHERE id = ?", Integer.class, id);
    }

    private int drainQueue() {
        int count = 0;
        org.springframework.amqp.core.Message msg;
        while ((msg = rabbitTemplate.receive("notification.dispatch.queue", 300)) != null) {
            count++;
        }
        return count;
    }
}
