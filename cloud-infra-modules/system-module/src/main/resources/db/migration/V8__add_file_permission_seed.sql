-- 文件服务功能级权限种子数据（P1-R5 纵深防御）
-- 细粒度资源权限（OWNER/READ/WRITE）在 file-service FilePermissionService 内校验，
-- 此处补充功能级 @PreAuthorize 门槛，与全项目 hasPermi 约定一致
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(20, '文件上传', 'file:file:upload', '/file/upload', 'POST'),
(21, '文件更新', 'file:file:update', '/file/update/{fileId}', 'PUT'),
(22, '文件下载', 'file:file:download', '/file/download/{fileId}', 'GET'),
(23, '文件历史', 'file:file:history', '/file/history/{fileId}', 'GET'),
(24, '文件搜索', 'file:file:search', '/file/search', 'GET')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员（role_id=1）自动获得全量权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission
ON CONFLICT (role_id, permission_id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
