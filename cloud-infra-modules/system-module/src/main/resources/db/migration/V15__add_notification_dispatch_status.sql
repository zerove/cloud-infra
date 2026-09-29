-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V15__add_notification_dispatch_status.sql
-- 描述: sys_notification 新增分发状态列, 使通知行自身充当发件箱记录
--
-- 背景 (issue 27 决策 2):
-- 通知渠道分发原为 "事务提交后 → 内存线程池异步执行", 存在两个真实缺陷:
--   1. 崩溃丢失: 事务已提交但分发任务未执行时进程崩溃, 通知永久丢失且无痕迹;
--   2. 失败无重试: 渠道发送失败仅记 sys_notification_log, 无重试/死信/告警。
--
-- 决策: 不引入 Outbox 框架 (独立表 + 投递器 + AOP 注解, 767 行)。
-- 项目已有 RabbitMQ 重试 + DLQ 基础设施, 覆盖"失败重试"; 而"崩溃丢失"用
-- 本表的 dispatch_status + 定时补扫覆盖即可, 无需独立发件箱表。
--
-- 方案:
--   dispatch_status SMALLINT  0=待分发 1=分发中 2=已分发 3=分发失败
--   dispatch_attempts SMALLINT 已尝试次数, 用于补扫重试上限
--
-- 与 status 的关系 (两者正交, 需同时看):
--   status          = 用户是否已读  (UNREAD / SENT / READ)
--   dispatch_status = 是否已投递到渠道 (PENDING / DISPATCHING / DISPATCHED / FAILED)
-- 原先无法区分"从未分发"与"已分发但未读", 这正是需要新增 dispatch_status 的原因。
--
-- 存量数据处理:
-- 存量行的 status 已是 UNREAD 或 SENT, 无法回溯其真实分发结果。
-- 一律置为 DISPATCHED(2): 保守假设已投递, 避免补扫重投造成用户收到重复通知。
-- 只有新写入的通知才是 PENDING(0), 参与补扫。

ALTER TABLE sys_notification ADD COLUMN IF NOT EXISTS dispatch_status SMALLINT NOT NULL DEFAULT 2;
ALTER TABLE sys_notification ADD COLUMN IF NOT EXISTS dispatch_attempts SMALLINT NOT NULL DEFAULT 0;

-- 存量行显式标记为已分发（DEFAULT 2 已覆盖, 此处仅为可读性保留）
UPDATE sys_notification SET dispatch_status = 2 WHERE dispatch_status IS NULL;

-- 补扫查询路径: WHERE dispatch_status = 0 AND create_time < ?
CREATE INDEX IF NOT EXISTS idx_notification_dispatch ON sys_notification(dispatch_status, create_time);

COMMENT ON COLUMN sys_notification.dispatch_status IS '分发状态: 0=待分发 1=分发中 2=已分发 3=分发失败 (issue 27 决策 2)';
COMMENT ON COLUMN sys_notification.dispatch_attempts IS '已尝试分发次数, 补扫达到上限后置 FAILED';
