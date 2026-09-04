-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V14__add_tenant_id_columns.sql
-- 描述: M6-01 多租户数据隔离 - 业务表新增 tenant_id 列 + 索引
--
-- 背景:
-- 多租户基础设施 (TenantContext / TenantContextFilter) 已就位, 但所有业务表缺 tenant_id 列,
-- TenantSqlInterceptor 无法自动追加 WHERE tenant_id = ? 过滤.
-- 本 migration 为 9 张核心业务表添加 tenant_id (默认 'default' 兼容历史数据), 并建立 B-tree 索引
-- 用于 WHERE tenant_id = ? / WHERE tenant_id = ? AND ... 的高频查询路径.
--
-- 表选择 (按业务核心度排序):
-- 1. sys_post           - 岗位 (RuoYi 主表, codegen 灰度迁移验证表)
-- 2. sys_user           - 用户 (鉴权核心)
-- 3. sys_dept           - 部门 (组织架构)
-- 4. sys_role           - 角色 (RBAC)
-- 5. sys_menu           - 菜单 (前端权限)
-- 6. sys_permission     - 权限点 (RBAC)
-- 7. file_metadata      - 文件元数据 (file-service, 跨模块)
-- 8. sys_notification   - 通知主表 (notification-service)
-- 9. sys_notification_preference - 通知偏好 (notification-service)
--
-- 不迁移的表:
-- - sys_dict_type/sys_dict_data/sys_config - 平台级字典, 全租户共享
-- - sys_user_role/sys_role_menu/sys_role_permission/sys_user_post - 关联表通过主表过滤
-- - sys_oper_log/sys_login_log/sys_tx_message - 日志表, 通常全租户共享
-- - file_permission/file_version/sys_storage_quota - 元数据/版本/配额, 通过 file_metadata 过滤

-- ========== sys_post 岗位表 ==========
ALTER TABLE sys_post ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_post_tenant_id ON sys_post(tenant_id);
COMMENT ON COLUMN sys_post.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== sys_user 用户表 ==========
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_user_tenant_id ON sys_user(tenant_id);
COMMENT ON COLUMN sys_user.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== sys_dept 部门表 ==========
ALTER TABLE sys_dept ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_dept_tenant_id ON sys_dept(tenant_id);
COMMENT ON COLUMN sys_dept.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== sys_role 角色表 ==========
ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_role_tenant_id ON sys_role(tenant_id);
COMMENT ON COLUMN sys_role.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== sys_menu 菜单表 ==========
ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_menu_tenant_id ON sys_menu(tenant_id);
COMMENT ON COLUMN sys_menu.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== sys_permission 权限表 ==========
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_permission_tenant_id ON sys_permission(tenant_id);
COMMENT ON COLUMN sys_permission.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== file_metadata 文件元数据 (file-service 模块) ==========
-- 注: file_metadata 表在 file-service/src/main/resources/schema.sql (test 单模块使用)
-- 主运行库 sys_* 表统一在 system-module/migration, 这里 IF EXISTS 保护
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'file_metadata') THEN
        ALTER TABLE file_metadata ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
        CREATE INDEX IF NOT EXISTS idx_file_metadata_tenant_id ON file_metadata(tenant_id);
        COMMENT ON COLUMN file_metadata.tenant_id IS '租户 ID (M6-01 多租户隔离)';
    END IF;
END
$$;

-- ========== sys_notification 通知主表 ==========
ALTER TABLE sys_notification ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_notification_tenant_id ON sys_notification(tenant_id);
COMMENT ON COLUMN sys_notification.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== sys_notification_preference 通知偏好 ==========
ALTER TABLE sys_notification_preference ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_sys_notification_preference_tenant_id ON sys_notification_preference(tenant_id);
COMMENT ON COLUMN sys_notification_preference.tenant_id IS '租户 ID (M6-01 多租户隔离)';

-- ========== 复合索引 (覆盖高频 tenant + status 查询) ==========
-- user 列表按 tenant + status 过滤是常见路径 (登录态查有效用户)
CREATE INDEX IF NOT EXISTS idx_sys_user_tenant_status ON sys_user(tenant_id, status);
-- 通知按 tenant + user + status 查未读
CREATE INDEX IF NOT EXISTS idx_sys_notification_tenant_user_status ON sys_notification(tenant_id, user_id, status);