-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V5__add_performance_indexes.sql
-- 描述: 性能优化索引（PostgreSQL 方言）

-- 用户表索引
CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_sys_user_dept_status ON sys_user(dept_id, status);
CREATE INDEX IF NOT EXISTS idx_sys_user_create_time ON sys_user(create_time);
CREATE INDEX IF NOT EXISTS idx_sys_user_role_id ON sys_user(role_id);

-- 部门表索引
CREATE INDEX IF NOT EXISTS idx_sys_dept_parent_id ON sys_dept(parent_id);

-- 角色表索引
CREATE INDEX IF NOT EXISTS idx_sys_role_key ON sys_role(role_key);

-- 菜单表索引
CREATE INDEX IF NOT EXISTS idx_sys_menu_parent_id ON sys_menu(parent_id);

-- 字典表索引
CREATE INDEX IF NOT EXISTS idx_sys_dict_type_code ON sys_dict_type(dict_type);
CREATE INDEX IF NOT EXISTS idx_sys_dict_data_type ON sys_dict_data(dict_type);

-- 日志表索引
CREATE INDEX IF NOT EXISTS idx_sys_oper_log_create_time ON sys_oper_log(create_time);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_login_time ON sys_login_log(login_time);

-- 事务消息表索引
CREATE INDEX IF NOT EXISTS idx_sys_tx_message_status ON sys_tx_message(status, create_time);
