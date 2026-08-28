-- notification-service 功能级权限种子数据
-- notification 控制器 @PreAuthorize 使用 notification:* 权限项,
-- V1-V9 迁移未插入对应权限且未绑定到 role_id=1 (超级管理员)。
-- 这里补充 5 个核心权限, 超级管理员自动获得, 修复 e2e/全链路 API 验证中
-- notification 端点对 admin 返回 500 (AccessDeniedException) 的问题。
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(30, '通知发送', 'notification:send',    '/notification/send',    'POST'),
(31, '通知历史', 'notification:history',  '/notification/history',  'GET'),
(32, '通知搜索', 'notification:search',   '/notification/search',   'GET'),
(33, '通知已读', 'notification:read',     '/notification/read',     'POST'),
(34, '通知审计', 'notification:logs',     '/notification/logs',     'GET')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员 (role_id=1) 自动获得新增权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission WHERE id BETWEEN 30 AND 34
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 推进序列, 避免后续程序插入与显式 id 冲突
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
