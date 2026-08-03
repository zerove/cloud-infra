-- Flyway 基线迁移脚本 (PostgreSQL 16)
-- 版本: V1__init_schema.sql
-- 描述: 初始化核心数据库架构（表结构与实体模型对齐）

-- 创建扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ========== 用户表 ==========
CREATE TABLE IF NOT EXISTS sys_user (
    user_id         BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    password        VARCHAR(100) NOT NULL,
    nick_name       VARCHAR(50),
    phone_number    VARCHAR(20),
    email           VARCHAR(100),
    dept_id         BIGINT,
    role_id         BIGINT,
    post_id         BIGINT,
    status          SMALLINT DEFAULT 1,
    last_login_time TIMESTAMP,
    last_login_ip   VARCHAR(50),
    create_by       VARCHAR(64),
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by       VARCHAR(64),
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_user IS '用户表';
COMMENT ON COLUMN sys_user.user_id IS '用户ID';
COMMENT ON COLUMN sys_user.username IS '登录账号';
COMMENT ON COLUMN sys_user.nick_name IS '用户昵称';
COMMENT ON COLUMN sys_user.phone_number IS '手机号码';
COMMENT ON COLUMN sys_user.status IS '状态: 0-禁用, 1-启用';
COMMENT ON COLUMN sys_user.dept_id IS '部门ID';
COMMENT ON COLUMN sys_user.role_id IS '角色ID';

-- ========== 部门表 ==========
CREATE TABLE IF NOT EXISTS sys_dept (
    dept_id     BIGSERIAL PRIMARY KEY,
    parent_id   BIGINT DEFAULT 0,
    ancestors   VARCHAR(500) DEFAULT '',
    dept_name   VARCHAR(50) NOT NULL,
    order_num   INT DEFAULT 0,
    leader      VARCHAR(20),
    phone       VARCHAR(20),
    email       VARCHAR(100),
    status      CHAR(1) DEFAULT '0',
    del_flag    CHAR(1) DEFAULT '0',
    create_by   VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_dept IS '部门表';

-- ========== 角色表 ==========
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGSERIAL PRIMARY KEY,
    role_name   VARCHAR(50) NOT NULL,
    role_key    VARCHAR(50) NOT NULL UNIQUE,
    role_order  INT DEFAULT 0,
    status      SMALLINT DEFAULT 1,
    data_scope  CHAR(1) DEFAULT '1',
    remark      VARCHAR(255),
    create_by   VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_role IS '角色表';
COMMENT ON COLUMN sys_role.data_scope IS '数据范围（1：全部 2：自定义 3：本部门 4：本部门及以下 5：仅本人）';

-- ========== 菜单表 ==========
CREATE TABLE IF NOT EXISTS sys_menu (
    id             BIGSERIAL PRIMARY KEY,
    menu_name      VARCHAR(64) NOT NULL,
    parent_id      BIGINT DEFAULT 0,
    order_num      INT DEFAULT 0,
    path           VARCHAR(200) DEFAULT '',
    component      VARCHAR(255),
    menu_type      CHAR(1) DEFAULT 'M',
    perms          VARCHAR(100),
    status         SMALLINT DEFAULT 0,
    frame_status   SMALLINT DEFAULT 1,
    visible_status SMALLINT DEFAULT 1,
    create_by      VARCHAR(64),
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by      VARCHAR(64),
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_menu IS '菜单权限表';
COMMENT ON COLUMN sys_menu.menu_type IS '菜单类型 (M:目录, C:菜单, F:按钮)';

-- ========== 权限表 ==========
CREATE TABLE IF NOT EXISTS sys_permission (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(50) NOT NULL,
    perms   VARCHAR(100),
    path    VARCHAR(200),
    method  VARCHAR(10) DEFAULT 'ALL',
    deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_permission IS '权限信息表';
COMMENT ON COLUMN sys_permission.perms IS '权限标识（如：user:add）';

-- ========== 字典类型表 ==========
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id          BIGSERIAL PRIMARY KEY,
    dict_name   VARCHAR(100) NOT NULL,
    dict_type   VARCHAR(100) NOT NULL UNIQUE,
    status      CHAR(1) DEFAULT '0',
    remark      VARCHAR(500),
    create_by   VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_dict_type IS '字典类型表';

-- ========== 字典数据表 ==========
CREATE TABLE IF NOT EXISTS sys_dict_data (
    id          BIGSERIAL PRIMARY KEY,
    dict_sort   INT DEFAULT 0,
    dict_label  VARCHAR(100) DEFAULT '',
    dict_value  VARCHAR(100) DEFAULT '',
    dict_type   VARCHAR(100) DEFAULT '',
    css_class   VARCHAR(100),
    list_class  VARCHAR(100),
    is_default  CHAR(1) DEFAULT 'N',
    status      CHAR(1) DEFAULT '0',
    remark      VARCHAR(500),
    create_by   VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_dict_data IS '字典数据表';

-- ========== 参数配置表 ==========
CREATE TABLE IF NOT EXISTS sys_config (
    id           BIGSERIAL PRIMARY KEY,
    config_name  VARCHAR(100) DEFAULT '',
    config_key   VARCHAR(100) DEFAULT '',
    config_value VARCHAR(500) DEFAULT '',
    config_type  CHAR(1) DEFAULT 'N',
    remark       VARCHAR(500),
    create_by    VARCHAR(64),
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by    VARCHAR(64),
    update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT DEFAULT 0
);

COMMENT ON TABLE sys_config IS '参数配置表';

-- ========== 岗位表 ==========
CREATE TABLE IF NOT EXISTS sys_post (
    post_id     BIGSERIAL PRIMARY KEY,
    post_code   VARCHAR(64) NOT NULL,
    post_name   VARCHAR(50) NOT NULL,
    post_sort   INT NOT NULL DEFAULT 0,
    status      CHAR(1) NOT NULL DEFAULT '0',
    remark      VARCHAR(500),
    create_by   VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_post IS '岗位表';

-- ========== 关联表 ==========
CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS sys_user_post (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, post_id)
);

CREATE TABLE IF NOT EXISTS sys_post_permission (
    post_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (post_id, permission_id)
);

CREATE TABLE IF NOT EXISTS sys_role_dept (
    role_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, dept_id)
);

-- ========== 初始数据 ==========
-- 超级管理员角色
INSERT INTO sys_role (id, role_name, role_key, role_order, status, data_scope, remark, create_time, update_time)
VALUES (1, '超级管理员', 'SUPER_ADMIN', 1, 1, '1', '系统内置超级管理员角色', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 普通用户角色
INSERT INTO sys_role (id, role_name, role_key, role_order, status, data_scope, remark, create_time, update_time)
VALUES (2, '普通用户', 'USER', 2, 1, '4', '普通用户角色', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 根部门
INSERT INTO sys_dept (dept_id, parent_id, ancestors, dept_name, order_num, leader, phone, email, status, del_flag, create_time, update_time)
VALUES (1, 0, '0', '总公司', 1, 'admin', '13800000000', 'admin@example.com', '0', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (dept_id) DO NOTHING;

-- 超级管理员用户 (密码: admin123, BCrypt加密)
INSERT INTO sys_user (user_id, username, password, nick_name, email, phone_number, status, dept_id, role_id, create_time, update_time)
VALUES (1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '超级管理员', 'admin@example.com', '13800000000', 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (user_id) DO NOTHING;

-- 菜单数据
INSERT INTO sys_menu (id, menu_name, parent_id, order_num, path, component, menu_type, perms, status, create_time, update_time) VALUES
(1, '系统管理', 0, 1, '/system', 'Layout', 'M', '', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, '用户管理', 1, 1, '/system/user', 'system/user/index', 'C', 'system:user:list', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, '角色管理', 1, 2, '/system/role', 'system/role/index', 'C', 'system:role:list', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, '菜单管理', 1, 3, '/system/menu', 'system/menu/index', 'C', 'system:menu:list', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, '部门管理', 1, 4, '/system/dept', 'system/dept/index', 'C', 'system:dept:list', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, '字典管理', 1, 5, '/system/dict', 'system/dict/index', 'C', 'system:dict:list', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 角色菜单关联
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 权限数据
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(1, '用户查询', 'system:user:list', '/system/user', 'GET'),
(2, '用户新增', 'system:user:add', '/system/user', 'POST'),
(3, '用户修改', 'system:user:edit', '/system/user', 'PUT'),
(4, '用户删除', 'system:user:remove', '/system/user', 'DELETE'),
(5, '角色查询', 'system:role:list', '/system/role', 'GET'),
(6, '角色新增', 'system:role:add', '/system/role', 'POST'),
(7, '角色修改', 'system:role:edit', '/system/role', 'PUT'),
(8, '角色删除', 'system:role:remove', '/system/role', 'DELETE'),
(9, '菜单查询', 'system:menu:list', '/system/menu', 'GET'),
(10, '部门查询', 'system:dept:list', '/system/dept', 'GET'),
(11, '字典查询', 'system:dict:list', '/system/dict', 'GET')
ON CONFLICT (id) DO NOTHING;

-- 角色权限关联（超级管理员全量权限）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 岗位数据
INSERT INTO sys_post (post_id, post_code, post_name, post_sort, status, remark, create_time, update_time) VALUES
(1, 'ceo', '董事长', 1, '0', '负责人', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 'se', '项目经理', 2, '0', '项目经理', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 'hr', '人力资源', 3, '0', 'HR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, 'user', '普通员工', 4, '0', '普通员工', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (post_id) DO NOTHING;

-- 字典类型
INSERT INTO sys_dict_type (id, dict_name, dict_type, status, remark, create_time, update_time) VALUES
(1, '用户性别', 'sys_user_sex', '0', '用户性别列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, '菜单状态', 'sys_show_hide', '0', '菜单状态列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, '系统开关', 'sys_normal_disable', '0', '系统开关列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, '任务状态', 'sys_job_status', '0', '任务状态列表', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 字典数据
INSERT INTO sys_dict_data (id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, remark, create_time, update_time) VALUES
(1, 1, '男', '0', 'sys_user_sex', 'primary', '', 'Y', '0', '性别男', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 1, '女', '1', 'sys_user_sex', 'danger', '', 'N', '0', '性别女', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 1, '未知', '2', 'sys_user_sex', '', '', 'N', '0', '性别未知', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, 1, '显示', '0', 'sys_show_hide', 'primary', '', 'Y', '0', '显示菜单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, 1, '隐藏', '1', 'sys_show_hide', 'danger', '', 'N', '0', '隐藏菜单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, 1, '正常', '0', 'sys_normal_disable', 'primary', '', 'Y', '0', '正常状态', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(7, 1, '停用', '1', 'sys_normal_disable', 'danger', '', 'N', '0', '停用状态', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 参数配置
INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, remark, create_time, update_time)
VALUES (1, '用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y', '初始化密码 123456', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
