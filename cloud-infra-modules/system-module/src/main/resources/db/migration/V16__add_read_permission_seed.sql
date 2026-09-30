-- 补充只读端点的权限种子（2026-09-30 审计）
--
-- 背景: 反射级扫描发现 3 个端点只要求"已认证"而未要求任何权限, 同一控制器内
-- 其余端点均有 @PreAuthorize。属于"认证通过即可越权读"的横向暴露面:
--   - GET /system/rbac/user/{userId}/permissions  任意用户可读他人权限集 (权限枚举)
--   - GET /system/resource-mapping/effective-nodes 任意用户可读资源拓扑
--   - GET /system/permission/list                任意用户可拉全量权限目录
-- id 41-43: 承接 V8/V10/V11 已用至 40。
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(41, '用户权限查询', 'system:rbac:query', '/system/rbac/user/{userId}/permissions', 'GET'),
(42, '资源拓扑查询', 'system:resource:query', '/system/resource-mapping/effective-nodes', 'GET'),
(43, '权限目录查询', 'system:permission:list', '/system/permission/list', 'GET')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员 (role_id=1) 自动获得新增权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission WHERE id BETWEEN 41 AND 43
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 推进序列, 避免后续程序插入与显式 id 冲突
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
