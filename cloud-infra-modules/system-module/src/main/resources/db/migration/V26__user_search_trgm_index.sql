-- sys_user 用户名模糊检索的性能修复 (#17)。
--
-- 起因: SysUserMapper.xml#selectUserPage 的检索条件是
--   AND u.username like concat('%', #{user.username}, '%')
-- 前导通配符使 B-Tree 索引完全不可用, 只能全表扫描; 该 mapper 里自己留了
-- "性能预警"注释。sys_user 是管理端最常被检索的表, 故引入 pg_trgm GIN 索引。
--
-- 约定与 V22/V23/V24 一致: 全部 IF NOT EXISTS, 可重复执行, 不含 DROP / 数据变更。
--
-- 范围说明: 只索引 username。全仓对 sys_user 的 LIKE 查询仅此一处;
-- nick_name 没有任何 LIKE 调用点, 为它建索引会成为无人使用的死索引。
-- 守护: UserSearchTrgmIndexGuardTest 反查"每个 trgm 索引列都必须有 LIKE 调用点"。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_sys_user_username_trgm
    ON sys_user USING gin (username gin_trgm_ops);
