-- Outbox 事件表
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

COMMENT ON TABLE outbox_event IS 'Outbox 事件表 - 事务性事件发布';
COMMENT ON COLUMN outbox_event.aggregate_type IS '聚合根类型';
COMMENT ON COLUMN outbox_event.aggregate_id IS '聚合根ID';
COMMENT ON COLUMN outbox_event.event_type IS '事件类型';
COMMENT ON COLUMN outbox_event.payload IS '事件负载(JSON)';
COMMENT ON COLUMN outbox_event.binding_name IS 'Spring Cloud Stream 绑定名';
COMMENT ON COLUMN outbox_event.status IS '状态: PENDING/DISPATCHED/FAILED';
COMMENT ON COLUMN outbox_event.retry_count IS '重试次数';
COMMENT ON COLUMN outbox_event.tenant_id IS '租户ID';
COMMENT ON COLUMN outbox_event.idempotency_key IS '幂等键';

CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_event(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_idempotency ON outbox_event(idempotency_key);