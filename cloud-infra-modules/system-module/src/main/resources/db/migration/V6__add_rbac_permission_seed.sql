-- 补充 RBAC/权限/资源/插件管理权限种子数据
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(12, '角色权限分配', 'system:rbac:assign-role', '/system/rbac/user/{userId}/roles', 'POST'),
(13, '权限分配', 'system:rbac:assign-perm', '/system/rbac/role/{roleId}/permissions', 'POST'),
(14, '权限新增', 'system:permission:add', '/system/permission', 'POST'),
(15, '资源绑定', 'system:resource:bind', '/system/resource-mapping/bind', 'POST'),
(16, '插件卸载', 'system:plugin:unload', '/system/plugins/{pluginId}', 'DELETE'),
(17, 'AI 问答', 'system:ai:chat', '/system/ai/chat', 'GET'),
(18, '审计查询', 'system:audit:list', '/system/audit/timeline', 'GET'),
(19, '监控快照', 'system:monitoring:list', '/system/monitoring/snapshot', 'GET')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员（role_id=1）自动获得全量权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 推进序列，避免后续程序插入与显式 id 冲突
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));