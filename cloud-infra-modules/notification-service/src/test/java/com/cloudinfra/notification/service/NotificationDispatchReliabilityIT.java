package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationDispatchMapper;
import com.cloudinfra.test.support.containers.InfrastructureContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知分发可靠性集成测试 (issue 27 决策 2)
 *
 * <p><b>只覆盖单元测试覆盖不到的部分</b>: 单元测试用 mock 验证控制流, 无法发现
 * 真实数据库/消息中间件层面的问题。本 IT 用真实 PostgreSQL 16 + RabbitMQ 3.13 验证:
 *
 * <ol>
 *   <li><b>枚举参数绑定</b>: {@code claimDispatch} 的 {@code DispatchStatus} 形参
 *       需正确绑定到 {@code SMALLINT} 列。MyBatis 对枚举参数默认用
 *       {@code EnumTypeHandler} 取 {@code name()}（字符串 {@code "PENDING"}）,
 *       与 smallint 列比较会失败或永不命中 —— 这是 mock 测不出的真实缺陷</li>
 *   <li><b>陈旧扫描查询</b>: {@code dispatch_status IN (0,1) AND create_time < ?}</li>
 *   <li><b>并发 CAS 互斥</b>: 多消费者抢同一行, 只有一个能抢到</li>
 *   <li><b>消息持久性</b>: 通知 ID 真实落到 RabbitMQ 队列中</li>
 *   <li><b>状态机完整性</b>: claim → dispatched / release → pending → failed</li>
 * </ol>
 */
@Testcontainers
@SpringBootTest(classes = NotificationDispatchReliabilityIT.DispatchTestApplication.class)
class NotificationDispatchReliabilityIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("cloud_infra")
        .withUsername("postgres")
        .withPassword("password")
        // 受控对照实测(2026-10-08): 本机 n=1 时 postgres 启动 109 秒,
        // n=3 并发时最长 125 秒 / 总计 173 秒 —— Testcontainers 默认 60 秒不够。
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-alpine")
        // 本轮 RabbitMQ 亦出现 1 次启动失败, 同因: 预算不足而非配置错误。
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    /**
     * 最小手工装配: 仅 JDBC + RabbitTemplate + 被测 Mapper。
     * 刻意不用 @EnableAutoConfiguration —— 那会拉起整个框架自动配置链
     * (Redisson / Sentinel / Nacos 等), 与被测行为无关且需额外容器。
     */
    @Configuration(proxyBeanMethods = false)
    static class DispatchTestApplication {

        @Bean
        DataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(postgres.getJdbcUrl());
            cfg.setUsername(postgres.getUsername());
            cfg.setPassword(postgres.getPassword());
            // 并发用例需同时容纳主线程 + 4 个消费者线程
            cfg.setMaximumPoolSize(10);
            return new HikariDataSource(cfg);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        CachingConnectionFactory rabbitConnectionFactory() {
            var factory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());
            factory.setUsername(rabbit.getAdminUsername());
            factory.setPassword(rabbit.getAdminPassword());
            return factory;
        }

        @Bean
        RabbitAdmin rabbitAdmin(CachingConnectionFactory cf) {
            return new RabbitAdmin(cf);
        }

        @Bean
        Queue notificationDispatchQueue() {
            return org.springframework.amqp.core.QueueBuilder
                .durable("notification.dispatch.queue").build();
        }

        @Bean
        RabbitTemplate rabbitTemplate(CachingConnectionFactory cf) {
            return new RabbitTemplate(cf);
        }

        /**
         * <b>必须用生产同款 MyBatis-Plus 栈</b>（{@code MybatisSqlSessionFactoryBean},
         * 与 {@code MybatisPlusPlatformAutoConfiguration} 一致）。
         *
         * <p>本 IT 的核心目的就是验证 {@code DispatchStatus} 形参能否正确绑定到
         * {@code SMALLINT} 列。这取决于 MyBatis-Plus 为带 {@code @EnumValue} 的枚举
         * 注册的 {@code CompositeEnumTypeHandler} —— 若改用原生 MyBatis,
         * 默认 {@code EnumTypeHandler} 取 {@code name()}（字符串）, 会得到
         * "operator does not exist: smallint = character varying" 的假阳性结论。
         * 桩化或 mock 同样会让该验证失去意义。</p>
         */
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean factoryBean =
                new com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setTypeAliasesPackage("com.cloudinfra.notification.domain");
            // 显式注册 Mapper 接口, 避免依赖包扫描
            factoryBean.setMapperLocations(new org.springframework.core.io.Resource[0]);
            com.baomidou.mybatisplus.core.MybatisConfiguration cfg =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
            cfg.setEnvironment(new org.apache.ibatis.mapping.Environment(
                "it", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                dataSource));
            cfg.addMapper(NotificationDispatchMapper.class);
            factoryBean.setConfiguration(cfg);
            return factoryBean.getObject();
        }

        @Bean
        NotificationDispatchMapper notificationDispatchMapper(SqlSessionFactory factory) {
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
    @Autowired private Queue dispatchQueue;

    @BeforeEach
    void setUp() {
        jdbc.execute(DDL);
        jdbc.update("DELETE FROM sys_notification");
        rabbitAdmin.deleteQueue("notification.dispatch.queue");
        rabbitAdmin.declareQueue(dispatchQueue);
    }

    @Test
    @DisplayName("枚举参数正确绑定到 SMALLINT 列: CAS 抢占能命中 PENDING 行")
    void claimBindsEnumToSmallintColumn() {
        // 这是 mock 测不出的真实风险: MyBatis 默认 EnumTypeHandler 取 name() ("PENDING"),
        // 与 smallint 列比较会导致抢占永远失败 (affected rows = 0)
        Long id = insert(PENDING, 0, now());

        int claimed = mapper.claimDispatch(id, DispatchStatus.PENDING);

        assertThat(claimed).as("枚举形参应能命中 smallint 列上的 PENDING(0)").isEqualTo(1);
        assertThat(dispatchStatusOf(id)).isEqualTo(1);
        assertThat(dispatchAttemptsOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("CAS 互斥: 状态不匹配时抢占失败")
    void claimFailsWhenStateMismatch() {
        Long id = insert(DISPATCHING, 1, now());

        int claimed = mapper.claimDispatch(id, DispatchStatus.PENDING);

        assertThat(claimed).isZero();
        assertThat(dispatchStatusOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发抢占: 多消费者抢同一行只有一方成功")
    void concurrentClaimsAreMutuallyExclusive() throws Exception {
        Long id = insert(PENDING, 0, now());
        int consumers = 4;

        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        for (int w = 0; w < consumers; w++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                    if (mapper.claimDispatch(id, DispatchStatus.PENDING) == 1) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // 核心断言: 同一条通知不会被两个消费者同时分发 (否则用户收到重复通知)
        assertThat(winners.get()).as("同一行的分发权应唯一").isEqualTo(1);
        assertThat(dispatchAttemptsOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("补扫查询: 同时捞出陈旧的 PENDING 与 DISPATCHING, 排除已完成的")
    void staleScanCoversPendingAndDispatchingOnly() {
        Long stalePending = insert(PENDING, 0, now().minusHours(1));
        Long staleDispatching = insert(DISPATCHING, 1, now().minusHours(1));
        Long freshPending = insert(PENDING, 0, now().minusSeconds(10));
        Long done = insert(DISPATCHED, 1, now().minusHours(1));
        Long failed = insert(FAILED, 3, now().minusHours(1));

        List<Notification> stale =
            mapper.findStaleUndispatched(now().minusMinutes(2), 100);

        assertThat(stale).extracting(Notification::getId)
            .containsExactlyInAnyOrder(stalePending, staleDispatching);
        assertThat(stale).extracting(Notification::getId)
            .doesNotContain(freshPending, done, failed);
    }

    @Test
    @DisplayName("状态机: release 解卡后可再次抢占; markDispatched 终结")
    void stateMachineTransitions() {
        // 消费者中途崩溃留下 DISPATCHING → 补扫解卡 → 重新抢占 → 成功终结
        Long id = insert(DISPATCHING, 1, now().minusHours(1));

        assertThat(mapper.releaseDispatchClaim(id)).isEqualTo(1);
        assertThat(dispatchStatusOf(id)).isEqualTo(0);

        assertThat(mapper.claimDispatch(id, DispatchStatus.PENDING)).isEqualTo(1);
        assertThat(dispatchAttemptsOf(id)).isEqualTo(2);

        assertThat(mapper.markDispatched(id)).isEqualTo(1);
        assertThat(dispatchStatusOf(id)).isEqualTo(2);

        // 终态不可再被抢占
        assertThat(mapper.claimDispatch(id, DispatchStatus.PENDING)).isZero();
    }

    @Test
    @DisplayName("达上限标记 FAILED: 补扫不再重投")
    void exhaustedRowMarkedFailed() {
        Long id = insert(PENDING, 3, now().minusHours(1));

        assertThat(mapper.markDispatchFailed(id)).isEqualTo(1);

        assertThat(dispatchStatusOf(id)).isEqualTo(3);
        assertThat(mapper.findStaleUndispatched(now().minusMinutes(2), 100))
            .extracting(Notification::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("消息持久性: 通知 ID 真实落到 RabbitMQ 队列")
    void notificationIdLandsInRealQueue() {
        Long id = insert(PENDING, 0, now());

        rabbitTemplate.convertAndSend("notification.dispatch.queue", String.valueOf(id));

        // 从 Broker 实际取回, 证明消息真的落盘而非只调用了模板方法
        var message = rabbitTemplate.receive("notification.dispatch.queue", 10_000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(String.valueOf(id));
    }

    @Test
    @DisplayName("端到端: 崩溃遗留的 PENDING 行经补扫重投后可被消费者完成分发")
    void endToEndCrashRecovery() {
        // 模拟: 事务已提交, 但进程在投递前崩溃 —— 行留在 PENDING 且从未进过队列。
        // create_time 须早于补扫阈值, 否则仍处于 2 分钟缓冲窗口内 (设计上本就不应被捞起,
        // 以免与在途消费者抢单)。
        Long id = insert(PENDING, 0, now().minusHours(1));
        assertThat(rabbitTemplate.receive("notification.dispatch.queue", 500)).isNull();

        // 补扫: 捞出陈旧 PENDING → 重投 MQ
        List<Notification> stale = mapper.findStaleUndispatched(now().minusMinutes(2), 100);
        assertThat(stale).extracting(Notification::getId).contains(id);
        for (Notification n : stale) {
            rabbitTemplate.convertAndSend("notification.dispatch.queue", String.valueOf(n.getId()));
        }

        // 消费者: 取出消息 → CAS 抢占 → 分发成功 → 置 DISPATCHED
        var message = rabbitTemplate.receive("notification.dispatch.queue", 10_000);
        assertThat(message).isNotNull();
        Long consumedId = Long.valueOf(
            new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(mapper.claimDispatch(consumedId, DispatchStatus.PENDING)).isEqualTo(1);
        assertThat(mapper.markDispatched(consumedId)).isEqualTo(1);

        assertThat(dispatchStatusOf(id)).isEqualTo(2);
        // 已完成的行不再被补扫捞起, 避免重复打扰用户
        assertThat(mapper.findStaleUndispatched(now().minusMinutes(2), 100))
            .extracting(Notification::getId).doesNotContain(id);
    }

    // ========== 辅助 ==========

    private static final int PENDING = 0;
    private static final int DISPATCHING = 1;
    private static final int DISPATCHED = 2;
    private static final int FAILED = 3;

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }

    private Long insert(int dispatchStatus, int attempts, LocalDateTime createTime) {
        return jdbc.queryForObject(
            "INSERT INTO sys_notification (user_id, type, title, content, status, "
                + "dispatch_status, dispatch_attempts, create_time) "
                + "VALUES (1, 'EMAIL', 't', 'c', 0, ?, ?, ?) RETURNING id",
            Long.class, dispatchStatus, attempts, createTime);
    }

    private int dispatchStatusOf(Long id) {
        return jdbc.queryForObject(
            "SELECT dispatch_status FROM sys_notification WHERE id = ?", Integer.class, id);
    }

    private int dispatchAttemptsOf(Long id) {
        return jdbc.queryForObject(
            "SELECT dispatch_attempts FROM sys_notification WHERE id = ?", Integer.class, id);
    }
}
