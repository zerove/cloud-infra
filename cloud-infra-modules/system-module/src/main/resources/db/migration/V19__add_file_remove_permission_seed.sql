-- 文件删除端点的权限种子 (issue 34, 2026-10-07)
--
-- 背景: IFileService.delete 自 2026-09 起已实现完整 (存在性检查 → WRITE 权限 →
--   真实存储删除 → 配额释放 → 版本清理 → 元数据删除), 但 FileController 无任何
--   @DeleteMapping, 生产代码零调用 —— 能力存在却无法从接口触达。
--   本次按产品决策"开放"补上端点与功能级权限点。
--
-- id 44: V16 已用至 43, 且 sys_permission_id_seq 当前 last_value=43。
--
-- 硬删除说明: 该端点不可逆 —— 存储对象与元数据同时消失, 无回收站。
--   细粒度资源权限 (OWNER/READ/WRITE) 仍由 FilePermissionService 校验:
--   功能级 file:file:remove 是第一道门槛, WRITE 资源权限是第二道, 缺一不可。
INSERT INTO sys_permission (id, name, perms, path, method) VALUES
(44, '文件删除', 'file:file:remove', '/file/{fileId}', 'DELETE')
ON CONFLICT (id) DO NOTHING;

-- 超级管理员 (role_id=1) 自动获得新增权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission WHERE id = 44
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 推进序列, 避免后续程序插入与显式 id 冲突
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
