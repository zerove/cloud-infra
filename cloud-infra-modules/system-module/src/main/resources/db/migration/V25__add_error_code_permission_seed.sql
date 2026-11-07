-- 错误码查询端点的权限种子 (2026-10-17 审计)
--
-- 背景: ErrorCodeController 位于 cloud-infra-common, 被每个应用的
-- scanBasePackages("com.cloudinfra") 扫入, 于是 /api/error-codes 在**全部服务**上
-- 无鉴权暴露完整错误码表。仓库规则要求非 /internal/ 端点带 @PreAuthorize, 但
-- ControllerAuthorizationTest 只扫 com.cloudinfra.system, 结构上看不见 common 下的控制器。
--
-- 本次给控制器加类级 @PreAuthorize, 配套补权限点 system:error-code:list。
--
-- id 45: V19 已用至 44。
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(45, '错误码查询', 'system:error-code:list', '/api/error-codes', 'GET')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员 (role_id=1) 自动获得
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission WHERE id = 45
ON CONFLICT (role_id, permission_id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
