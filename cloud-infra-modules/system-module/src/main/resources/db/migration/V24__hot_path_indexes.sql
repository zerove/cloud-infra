-- 补齐热路径查询所缺的索引。
--
-- 起因 (2026-10-17 审计): 下列查询都打在没有索引的列上, 生产上是全表扫描。
-- 值得单独记录的是 V22 / V23 —— 它们建表时**一张索引都没建**, 而各自的测试用
-- schema.sql 里有对应索引, 于是"测试全绿"与"生产慢查询"长期并存, 没有任何环节报错。
--
-- 每条索引都对应一处真实 WHERE (随附于注释), 便于日后核对是否仍被使用:
--   sys_notification_schedule  NotificationScheduler:66-70  processed=false AND schedule_time<=now() LIMIT 100
--   sys_notification_log       NotificationServiceImpl:300 notification_id IN (...)
--   sys_notification_rule      NotificationRuleService:32-35 device_id=? AND enabled=true
--   file_version                getVersionHistory           file_id=? ORDER BY version_number DESC
--   sys_storage_quota           StorageQuotaService:getQuota user_id=?
--   sys_config                  selectConfigByKey            config_key=?
--   sys_user_role               SysPermissionMapper.xml:8    JOIN ... ON ur.role_id = rp.role_id
--   sys_resource_mapping        getEffectiveAccessNodes      resource_id=? AND resource_type=?
--
-- 全部 CREATE INDEX IF NOT EXISTS, 与 V22 / V23 保持同一约定:
--   · 存量库 —— 已存在则跳过
--   · 全新库 —— 正常创建
-- 幂等性: 本文件可重复执行; 不含 DROP / TRUNCATE / 数据变更。

CREATE INDEX IF NOT EXISTS idx_notif_schedule_pending
    ON sys_notification_schedule (processed, schedule_time);

CREATE INDEX IF NOT EXISTS idx_notif_log_notification
    ON sys_notification_log (notification_id, create_time);

CREATE INDEX IF NOT EXISTS idx_notif_rule_device_enabled
    ON sys_notification_rule (device_id, enabled);

CREATE INDEX IF NOT EXISTS idx_file_version_file_no
    ON file_version (file_id, version_number DESC);

CREATE INDEX IF NOT EXISTS idx_storage_quota_user
    ON sys_storage_quota (user_id);

CREATE INDEX IF NOT EXISTS idx_sys_config_key
    ON sys_config (config_key);

-- sys_user_role 主键是 (user_id, role_id), 因此按 role_id 单查无索引可用
CREATE INDEX IF NOT EXISTS idx_sys_user_role_role
    ON sys_user_role (role_id);

CREATE INDEX IF NOT EXISTS idx_resource_mapping_resource
    ON sys_resource_mapping (resource_id, resource_type);