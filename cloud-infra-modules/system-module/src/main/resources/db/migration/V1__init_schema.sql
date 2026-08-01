-- Flyway 基线迁移脚本
-- 版本: V1__init_schema.sql
-- 描述: 初始化数据库架构

-- 创建扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    real_name VARCHAR(50),
    email VARCHAR(100),
    phone VARCHAR(20),
    avatar VARCHAR(255),
    status SMALLINT DEFAULT 1,
    dept_id BIGINT,
    role_id BIGINT,
    last_login_time TIMESTAMP,
    last_login_ip VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_user IS '用户表';
COMMENT ON COLUMN sys_user.id IS '主键ID';
COMMENT ON COLUMN sys_user.username IS '用户名';
COMMENT ON COLUMN sys_user.password IS '密码';
COMMENT ON COLUMN sys_user.real_name IS '真实姓名';
COMMENT ON COLUMN sys_user.email IS '邮箱';
COMMENT ON COLUMN sys_user.phone IS '手机号';
COMMENT ON COLUMN sys_user.avatar IS '头像';
COMMENT ON COLUMN sys_user.status IS '状态: 0-禁用, 1-启用';
COMMENT ON COLUMN sys_user.dept_id IS '部门ID';
COMMENT ON COLUMN sys_user.role_id IS '角色ID';
COMMENT ON COLUMN sys_user.last_login_time IS '最后登录时间';
COMMENT ON COLUMN sys_user.last_login_ip IS '最后登录IP';

-- 部门表
CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    name VARCHAR(50) NOT NULL,
    sort INT DEFAULT 0,
    leader VARCHAR(20),
    phone VARCHAR(20),
    email VARCHAR(100),
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_dept IS '部门表';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    sort INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    data_scope SMALLINT DEFAULT 1,
    remark VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_role IS '角色表';

-- 菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    name VARCHAR(50) NOT NULL,
    path VARCHAR(200),
    component VARCHAR(255),
    permission VARCHAR(100),
    icon VARCHAR(50),
    type SMALLINT DEFAULT 1,
    sort INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    visible SMALLINT DEFAULT 1,
    cache SMALLINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_menu IS '菜单权限表';

-- 角色菜单关联表
CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);

COMMENT ON TABLE sys_role_menu IS '角色菜单关联表';

-- 字典类型表
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(100) NOT NULL UNIQUE,
    status SMALLINT DEFAULT 1,
    remark VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_dict_type IS '字典类型表';

-- 字典数据表
CREATE TABLE IF NOT EXISTS sys_dict_data (
    id BIGSERIAL PRIMARY KEY,
    dict_type_id BIGINT NOT NULL,
    label VARCHAR(100) NOT NULL,
    value VARCHAR(100) NOT NULL,
    sort INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    css_class VARCHAR(100),
    list_class VARCHAR(100),
    is_default SMALLINT DEFAULT 0,
    remark VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_dict_data IS '字典数据表';

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_sys_user_dept_id ON sys_user(dept_id);
CREATE INDEX IF NOT EXISTS idx_sys_user_role_id ON sys_user(role_id);
CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user(status);
CREATE INDEX IF NOT EXISTS idx_sys_dept_parent_id ON sys_dept(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_code ON sys_role(code);
CREATE INDEX IF NOT EXISTS idx_sys_menu_parent_id ON sys_menu(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_dict_type_code ON sys_dict_type(code);
CREATE INDEX IF NOT EXISTS idx_sys_dict_data_type_id ON sys_dict_data(dict_type_id);

-- 插入初始数据
-- 超级管理员角色
INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark, create_time, update_time) 
VALUES (1, '超级管理员', 'SUPER_ADMIN', 1, 1, 1, '系统内置超级管理员角色', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 普通用户角色
INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark, create_time, update_time) 
VALUES (2, '普通用户', 'USER', 2, 1, 4, '普通用户角色', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 根部门
INSERT INTO sys_dept (id, parent_id, name, sort, leader, phone, email, status, create_time, update_time) 
VALUES (1, 0, '总公司', 1, 'admin', '13800000000', 'admin@example.com', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 超级管理员用户 (密码: admin123, BCrypt加密)
INSERT INTO sys_user (id, username, password, real_name, email, phone, status, dept_id, role_id, create_time, update_time) 
VALUES (1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '超级管理员', 'admin@example.com', '13800000000', 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 菜单数据
INSERT INTO sys_menu (id, parent_id, name, path, component, permission, icon, type, sort, status, visible, cache, create_time, update_time) VALUES
(1, 0, '系统管理', '/system', 'Layout', '', 'setting', 0, 1, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 1, '用户管理', '/system/user', 'system/user/index', 'system:user:list', 'user', 1, 1, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 1, '角色管理', '/system/role', 'system/role/index', 'system:role:list', 'peoples', 1, 2, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, 1, '菜单管理', '/system/menu', 'system/menu/index', 'system:menu:list', 'tree-table', 1, 3, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, 1, '部门管理', '/system/dept', 'system/dept/index', 'system:dept:list', 'tree', 1, 4, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, 1, '字典管理', '/system/dict', 'system/dict/index', 'system:dict:list', 'dict', 1, 5, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 角色菜单关联
INSERT INTO sys_role_menu (role_id, menu_id) VALUES 
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 字典类型
INSERT INTO sys_dict_type (id, name, code, status, remark, create_time, update_time) VALUES
(1, '用户性别', 'sys_user_sex', 1, '用户性别列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, '菜单状态', 'sys_show_hide', 1, '菜单状态列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, '系统开关', 'sys_normal_disable', 1, '系统开关列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, '任务状态', 'sys_job_status', 1, '任务状态列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 字典数据
INSERT INTO sys_dict_data (id, dict_type_id, label, value, sort, status, css_class, list_class, is_default, remark, create_time, update_time) VALUES
(1, 1, '男', '0', 1, 1, '', '', 1, '性别男', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 1, '女', '1', 2, 1, '', '', 0, '性别女', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 1, '未知', '2', 3, 1, '', '', 0, '性别未知', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, 2, '显示', '0', 1, 1, 'primary', '', 1, '显示菜单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, 2, '隐藏', '1', 2, 1, 'danger', '', 0, '隐藏菜单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, 3, '正常', '0', 1, 1, 'primary', '', 1, '正常状态', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(7, 3, '停用', '1', 2, 1, 'danger', '', 0, '停用状态', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;