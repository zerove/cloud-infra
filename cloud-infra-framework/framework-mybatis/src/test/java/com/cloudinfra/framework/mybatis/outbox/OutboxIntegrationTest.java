package com.cloudinfra.framework.mybatis.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Outbox 模式集成测试（真实 PostgreSQL，P1-R1/R2 配套验证）。
 *
 * 覆盖四个关键行为：
 * 1. 事务原子性：业务事务提交时事件随事务持久化；回滚时事件一并回滚
 *    （R2——不存在"业务提交了但事件丢失"或"事件残留但业务回滚"的窗口）
 * 2. 幂等约束：相同 idempotency_key 直插被数据库唯一约束拒绝；
 *    publisher 层幂等预检则静默跳过、不产生第二行
 * 3. 并发派发互斥（R1）：多线程 lockAndFindPending 各抢到互不重叠的事件集
 *    （FOR UPDATE SKIP LOCKED），同一事件不会被重复消费，且全部被覆盖
 * 4. 生命周期状态机：markDispatched / markFailed / cleanDispatched 行为正确
 */
@Testcontainers
@SpringBootTest(classes = OutboxIntegrationTest.OutboxTestApplication.class)
class OutboxIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password");

    /**
     * 最小手工装配上下文：仅被测三件套 + JDBC 基础设施。
     * 刻意不用 @EnableAutoConfiguration——那会拉起整个框架自动配置链
     * （Redisson 连 redis:6379 等），与被测行为无关且需要额外容器。
     * 不引入 Flyway，建表由 getCreateTableDDL() 在 setUp 完成。
     */
    @Configuration(proxyBeanMethods = false)
    static class OutboxTestApplication {

        @Bean
        DataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(postgres.getJdbcUrl());
            cfg.setUsername(postgres.getUsername());
            cfg.setPassword(postgres.getPassword());
            // 并发派发用例需同时容纳主线程 + 4 个派发器线程的事务连接
            cfg.setMaximumPoolSize(10);
            return new HikariDataSource(cfg);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean
        OutboxEventRepository outboxEventRepository(JdbcTemplate jdbcTemplate) {
            return new OutboxEventRepository(jdbcTemplate);
        }

        @Bean
        OutboxEventPublisher outboxEventPublisher(OutboxEventRepository repository) {
            return new OutboxEventPublisher(repository, new ObjectMapper());
        }
    }

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private OutboxEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(OutboxEventRepository.getCreateTableDDL());
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    private TransactionTemplate newTx() {
        return new TransactionTemplate(transactionManager);
    }

    private int count() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void defaultPublisherEventCanBeDispatchedWithNullTenant() {
        newTx().executeWithoutResult(status -> publisher.publishTransactional(
            "order-events", "ORDER", "dispatch-1", "OrderCreated", "{\"number\":1}"));
        var bridge = mock(StreamBridge.class);
        when(bridge.send(eq("order-events"),
            any(Object.class))).thenReturn(true);
        var dispatcher = new OutboxEventDispatcher(repository, bridge, new ObjectMapper());
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 100);

        newTx().executeWithoutResult(status -> dispatcher.dispatchPendingEvents());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
            .isEqualTo("DISPATCHED");
        var sent = ArgumentCaptor.forClass(Object.class);
        verify(bridge).send(eq("order-events"), sent.capture());
        var envelope = (Map<?, ?>) sent.getValue();
        assertThat(envelope.get("eventId")).isNotNull();
        assertThat(envelope.containsKey("tenantId")).isTrue();
        assertThat(envelope.get("tenantId")).isNull();
        assertThat(envelope.get("payload")).isEqualTo(Map.of("number", 1));
    }

    @Test
    void committedBusinessTransactionPersistsOutboxEvent() {
        // 业务事务正常提交：事件随同一事务原子持久化
        newTx().executeWithoutResult(status ->
                publisher.publishTransactional("order-events", "ORDER", "A-1", "OrderCreated", "payload"));

        assertThat(count()).isEqualTo(1);
        assertThat(repository.existsByIdempotencyKey("ORDER:A-1:OrderCreated")).isTrue();
    }

    @Test
    void rolledBackBusinessTransactionRollsBackOutboxEvent() {
        // R2 核心语义：业务事务回滚时事件一并回滚——不会出现"业务没了但事件还在"的脏投递
        assertThatThrownBy(() -> newTx().executeWithoutResult(status -> {
                    publisher.publishTransactional("order-events", "ORDER", "B-1", "OrderCreated", "payload");
                    throw new IllegalStateException("simulate business failure");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
        // 幂等键也随回滚释放，重试同业务可重新发布
        assertThat(repository.existsByIdempotencyKey("ORDER:B-1:OrderCreated")).isFalse();
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedByDatabaseConstraint() {
        String sharedKey = "order-created:C-1";
        // 显式指定幂等键（业务侧按业务唯一性生成，与 aggregateId 无关）
        publisher.publishTransactional("order-events", "ORDER", "C-1", "OrderCreated", "p", null, sharedKey);

        // 数据库层兜底：绕过 publisher 预检直插同键 → 唯一约束拒绝
        assertThatThrownBy(() ->
                repository.insert(OutboxEvent.builder()
                        .aggregateType("ORDER")
                        .aggregateId("C-2")
                        .eventType("OrderCreated")
                        .payload("p")
                        .bindingName("order-events")
                        .tenantId(null)
                        .idempotencyKey(sharedKey)
                        .build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // publisher 层预检：同键二次发布静默跳过，不抛异常也不产生新行
        publisher.publishTransactional("order-events", "ORDER", "C-3", "OrderCreated", "p", null, sharedKey);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void concurrentDispatchersClaimDisjointEvents() throws Exception {
        // R1 核心语义：N 个派发器并发 lockAndFindPending，
        // 每个事件只被一个派发器抢到（SKIP LOCKED 保证不重叠、不阻塞）
        int totalEvents = 24;
        int dispatchers = 4;
        for (int i = 0; i < totalEvents; i++) {
            final String key = "key-D-" + i;
            repository.insert(OutboxEvent.builder()
                    .aggregateType("ORDER").aggregateId("D-" + i).eventType("OrderCreated")
                    .payload("{\"i\":" + i + "}").bindingName("order-events")
                    .tenantId("default").idempotencyKey(key).build());
        }

        ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
        CountDownLatch ready = new CountDownLatch(dispatchers);
        CountDownLatch go = new CountDownLatch(1);

        Set<Long> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateClaims = new AtomicInteger();

        for (int w = 0; w < dispatchers; w++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                    // 完整派发流程（与生产 OutboxEventDispatcher 一致）：
                    // 同一事务内 锁定 → 标记 DISPATCHED → 提交。
                    // 只 lock 不 mark 的话，前一事务提交释锁后，
                    // 后续派发器会把仍是 PENDING 的同批事件再捞一遍（那是合法重投，非锁失效）
                    List<OutboxEvent> batch = newTx().execute(status -> {
                        List<OutboxEvent> locked = repository.lockAndFindPending(100);
                        for (OutboxEvent ev : locked) {
                            repository.markDispatched(ev.getId());
                        }
                        return locked;
                    });
                    if (batch != null) {
                        for (OutboxEvent ev : batch) {
                            if (!claimed.add(ev.getId())) {
                                duplicateClaims.incrementAndGet();
                            }
                        }
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

        assertThat(duplicateClaims.get()).as("同一事件被多个派发器重复认领").isZero();
        assertThat(claimed.size()).as("SKIP LOCKED 下所有事件应被完整覆盖").isEqualTo(totalEvents);
    }

    @Test
    void lifecycleMarkDispatchedFailedAndClean() {
        Long id = newTx().execute(status -> {
            repository.insert(OutboxEvent.builder()
                    .aggregateType("ORDER").aggregateId("E-1").eventType("OrderCreated")
                    .payload("p").bindingName("order-events")
                    .tenantId("default").idempotencyKey("key-E-1").build());
            List<OutboxEvent> locked = repository.lockAndFindPending(10);
            assertThat(locked).hasSize(1);
            return locked.get(0).getId();
        });

        // 投递成功路径
        repository.markDispatched(id);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("DISPATCHED");

        // 失败重试路径：FAILED + retry_count 递增 + last_error 记录
        repository.insert(OutboxEvent.builder()
                .aggregateType("ORDER").aggregateId("E-2").eventType("OrderCreated")
                .payload("p").bindingName("order-events")
                .tenantId("default").idempotencyKey("key-E-2").build());
        Long failedId = jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_event WHERE idempotency_key = 'key-E-2'", Long.class);
        repository.markFailed(failedId, "broker unavailable");
        var row = jdbcTemplate.queryForMap(
                "SELECT status, retry_count, last_error FROM outbox_event WHERE id = ?", failedId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("broker unavailable");
        // FAILED 仍会被派发器重新捞起
        assertThat(repository.lockAndFindPending(10))
                .extracting(OutboxEvent::getId).contains(failedId);

        // 清理：DISPATCHED 且超期才删；PENDING/FAILED 不受影响
        int cleaned = repository.cleanDispatched(0, 10);
        assertThat(cleaned).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
        assertThat(repository.lockAndFindPending(10))
                .extracting(OutboxEvent::getId).containsExactly(failedId);
    }
}
