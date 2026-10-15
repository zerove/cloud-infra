-- 为 sys_oper_log.user_id 建索引。
--
-- AuditAggregatorService#getUnifiedTimeline 按 user_id 过滤 + create_time 倒序读取,
-- 而 V5 只为 create_time 建了索引。缺此索引时该查询只能扫 create_time 索引再回表过滤,
-- 且 sys_oper_log 随运行时间无界增长, 越往后越慢。
CREATE INDEX IF NOT EXISTS idx_sys_oper_log_user_id ON sys_oper_log(user_id);

-- 同一查询的排序方向与过滤方向一致, 复合索引可让排序也走索引。
CREATE INDEX IF NOT EXISTS idx_sys_oper_log_user_time ON sys_oper_log(user_id, create_time DESC);