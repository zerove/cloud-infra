-- 删除已无代码使用的 outbox_event 表。
--
-- Outbox 框架(OutboxEventRepository / OutboxAutoConfiguration 等)已在早前清理中删除,
-- 但 V2 建表迁移仍会创建该表。实测全仓已无任何 Java/XML/YAML 引用 outbox_event,
-- 残留的只有 spotbugs-exclude.xml 里一条指向已不存在类的抑制项。
--
-- 该表带 tenant_id 却不带 @TenantFilter 的 Mapper(拦截器对无注解 Mapper 放行),
-- 属多租户覆盖面的潜在缺口; 与其留一张无人用又无租户过滤的表, 不如删除。
DROP TABLE IF EXISTS outbox_event;