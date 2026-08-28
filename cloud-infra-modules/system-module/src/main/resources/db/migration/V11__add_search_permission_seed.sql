-- search-service 功能级权限种子数据
-- search 控制器 @PreAuthorize 使用 search:* 权限项,
-- V1-V9 迁移未插入对应权限且未绑定到 role_id=1 (超级管理员)。
-- 这里补充 6 个核心权限, 超级管理员自动获得, 与 V8/V10 风格一致。
-- 注意: search 端点当前 e2e 通过是因为 system 容器 F4 缓存命中了用户上下文,
-- 加本迁移后即使缓存失效也能正确鉴权。
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(35, '联邦搜索',   'search:unified',         '/search/unified',         'GET'),
(36, '操作日志',   'search:operlog',         '/search/operlog',         'GET'),
(37, '遥测历史',   'search:telemetry',       '/search/iot/telemetry',   'GET'),
(38, '索引列表',   'search:index:list',      '/search/indexes',         'GET'),
(39, '索引详情',   'search:index:detail',    '/search/indexes/{name}',  'GET'),
(40, '索引重建',   'search:index:rebuild',   '/search/indexes/{name}/rebuild', 'POST')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员 (role_id=1) 自动获得新增权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission WHERE id BETWEEN 35 AND 40
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 推进序列, 避免后续程序插入与显式 id 冲突
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
