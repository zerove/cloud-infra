-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V24__add_tenant_id_to_audit_log_tables.sql
-- 描述: M6-01 多租户数据隔离 - 审计日志表补 tenant_id 列 (issue 36 纵深防御)
--
-- 背景:
-- issue 36 已确认两层缺失: 端点未校验 userId 归属 + 审计源表本身无租户维度。
-- 本迁移为两个审计源表补上 tenant_id 列, 使后续 @TenantFilter 租户过滤在
-- 表层面也能真正成立 (纵深防御); 同时为 V14 "不迁移的日志表"口径下线.
--
-- 覆盖表:
-- - sys_oper_log        (system-module, 全链路行为审计)
-- - sys_notification_log (notification-service, 通知发送审计)
--
-- 口径:
-- - 存量行默认 tenant_id='default' (与 V14 中业务表 DEFAULT 'default' 一致);
-- - 新行由 TenantInsertInterceptor 按调用上下文自动追加;
-- - 查询由 @TenantFilter + TenantSqlInterceptor 按上下文租户过滤.
--
-- 幂等性: 可重复执行 (ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS).

-- ========== sys_oper_log 审计表 ==========
ALTER TABLE sys_oper_log ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_oper_log_tenant_id ON sys_oper_log(tenant_id);
COMMENT ON COLUMN sys_oper_log.tenant_id IS '租户 ID (M6-01 多租户隔离, issue 36 纵深防御)';

-- ========== sys_notification_log 审计表 ==========
ALTER TABLE sys_notification_log ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_notification_log_tenant_id ON sys_notification_log(tenant_id);
COMMENT ON COLUMN sys_notification_log.tenant_id IS '租户 ID (M6-01 多租户隔离, issue 36 纵深防御)';
