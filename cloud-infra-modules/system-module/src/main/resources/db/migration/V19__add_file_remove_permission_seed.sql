-- 为「删除文件」端点补权限种子 (Flyway 迁移脚本, PostgreSQL 16)
--
-- 背景: file-service 的删除端点 @PreAuthorize("@ss.hasPermi('file:file:remove')"),
--   但全仓无任何迁移种下该权限, 导致该端点对任何角色都不可用 ——
--   「能力存在 ≠ 能力被授予」。
--
-- id=44: 既有种子链止于 43 (V8/V10/V11/V16), 此处顺延。
-- ON CONFLICT DO NOTHING: 重复执行不报错, 也不覆盖既有定义。
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
    (44, '文件删除', 'file:file:remove', '/file/{fileId}', 'DELETE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
    SELECT 1, id FROM sys_permission WHERE id = 44
ON CONFLICT (role_id, permission_id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
