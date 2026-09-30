-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V17__add_notification_channels.sql
-- 描述: sys_notification 新增 channels 列, 持久化调用方请求的目标渠道
--
-- 背景 (2026-09-30 审计发现):
-- NotificationServiceImpl 分发时恒传 channels=null, 而 NotificationRouter 在渠道为空时
-- 会回落到 "所有可用渠道"。于是调用方明确要求 channels=["email"] 时, 用户会额外收到
-- 短信与 WebSocket 推送 —— 请求 1 个渠道, 实发 3 个, 属过度发送。
--
-- 根因是分发发生在 MQ 消费端, 消费时原始 NotificationSendRequest 早已不可得,
-- 只能从通知行还原目标渠道, 而通知行里根本没有存这个信息。
--
-- 为什么本脚本此前缺失 (本脚本即修复该缺陷):
-- 该列最初只加在 notification-service 的 schema.sql 里, 而 schema.sql 只在集成测试中
-- 通过 spring.sql.init 生效 (每次都是全新空库)。生产库 sys_notification 由
-- system-module 的 Flyway 迁移管理, 列不存在 —— 于是 findById 的 SELECT channels
-- 在生产会抛 column "channels" does not exist, 消费端每一条通知的分发都失败。
-- 即"测试全绿、生产必崩", 本地绿灯掩盖了缺失的迁移脚本。
--
-- 存量数据处理:
-- channels 为 NULL 表示该通知写于本列加入之前, 真实目标渠道已无从考证。
-- 一律保持 NULL —— NotificationDispatcher 对 NULL 的语义是"按偏好路由到所有可用渠道",
-- 与这些通知被写入时的实际行为一致, 因此不做回填, 避免凭空发明渠道偏好。

ALTER TABLE sys_notification ADD COLUMN IF NOT EXISTS channels VARCHAR(255);

COMMENT ON COLUMN sys_notification.channels IS
    '调用方请求的目标渠道 (逗号分隔的 channel_code, 如 email,sms)。分发在消费者侧异步执行, 原请求不可得, 故在此持久化。NULL = 写于本列加入之前, 按偏好路由';
