package com.cloudinfra.framework.mybatis.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件存储
 * 基于 JDBC 的轻量实现，业务事务内写入保证一致性
 */
@Repository
public class OutboxEventRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String TABLE = "outbox_event";

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) ->
        OutboxEvent.builder()
            .id(rs.getLong("id"))
            .aggregateType(rs.getString("aggregate_type"))
            .aggregateId(rs.getString("aggregate_id"))
            .eventType(rs.getString("event_type"))
            .payload(rs.getString("payload"))
            .bindingName(rs.getString("binding_name"))
            .status(rs.getString("status"))
            .retryCount(rs.getInt("retry_count"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .dispatchedAt(rs.getTimestamp("dispatched_at") != null
                ? rs.getTimestamp("dispatched_at").toLocalDateTime() : null)
            .lastError(rs.getString("last_error"))
            .tenantId(rs.getString("tenant_id"))
            .idempotencyKey(rs.getString("idempotency_key"))
            .build();

    public OutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入 Outbox 事件（必须在业务事务内调用）
     */
    @Transactional
    public void insert(OutboxEvent event) {
        jdbcTemplate.update("""
            INSERT INTO %s (aggregate_type, aggregate_id, event_type, payload,
                            binding_name, status, retry_count, created_at, tenant_id, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(TABLE),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getEventType(),
            event.getPayload(),
            event.getBindingName(),
            "PENDING",
            0,
            LocalDateTime.now(),
            event.getTenantId(),
            event.getIdempotencyKey()
        );
    }

    /**
     * 查询待投递事件（分页）
     */
    public List<OutboxEvent> findPending(int limit) {
        return jdbcTemplate.query("""
            SELECT * FROM %s
            WHERE status IN ('PENDING', 'FAILED')
            ORDER BY created_at ASC
            LIMIT ?
            """.formatted(TABLE),
            ROW_MAPPER, limit
        );
    }

    /**
     * 标记为已投递
     */
    @Transactional
    public void markDispatched(Long id) {
        jdbcTemplate.update("""
            UPDATE %s SET status = 'DISPATCHED', dispatched_at = ? WHERE id = ?
            """.formatted(TABLE),
            LocalDateTime.now(), id
        );
    }

    /**
     * 标记投递失败（递增重试次数）
     */
    @Transactional
    public void markFailed(Long id, String error) {
        jdbcTemplate.update("""
            UPDATE %s SET status = 'FAILED', retry_count = retry_count + 1, last_error = ? WHERE id = ?
            """.formatted(TABLE),
            error, id
        );
    }

    /**
     * 查询事件是否存在（幂等检查）
     */
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE + " WHERE idempotency_key = ?",
            Integer.class, idempotencyKey
        );
        return count != null && count > 0;
    }

    /**
     * 清理已投递的旧事件
     */
    @Transactional
    public int cleanDispatched(int olderThanDays, int limit) {
        return jdbcTemplate.update("""
            DELETE FROM %s
            WHERE status = 'DISPATCHED'
              AND dispatched_at < ?
              AND id IN (SELECT id FROM %s WHERE status = 'DISPATCHED' ORDER BY id LIMIT ?)
            """.formatted(TABLE, TABLE),
            LocalDateTime.now().minusDays(olderThanDays), limit
        );
    }

    /**
     * 创建 Outbox 表（Flyway 迁移脚本中调用，这里提供 DDL 参考）
     */
    public static String getCreateTableDDL() {
        return """
            CREATE TABLE IF NOT EXISTS outbox_event (
                id BIGSERIAL PRIMARY KEY,
                aggregate_type VARCHAR(50) NOT NULL,
                aggregate_id VARCHAR(50) NOT NULL,
                event_type VARCHAR(100) NOT NULL,
                payload TEXT NOT NULL,
                binding_name VARCHAR(100) NOT NULL,
                status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                retry_count INT NOT NULL DEFAULT 0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                dispatched_at TIMESTAMP,
                last_error TEXT,
                tenant_id VARCHAR(50),
                idempotency_key VARCHAR(100) UNIQUE
            );
            CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_event(status, created_at);
            """;
    }
}