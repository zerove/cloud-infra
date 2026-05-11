-- 优化用户表查询
CREATE INDEX idx_sys_user_dept_status ON sys_user(dept_id, status);
CREATE INDEX idx_sys_user_create_time ON sys_user(create_time);

-- 优化角色表查询
-- 假设存在 sys_role 表，需检查 migration 文件是否存在该表创建
-- 这里仅作为优化示例
