-- 修复超级管理员开箱零权限缺陷（跨服务链路 IT 排查发现）
--
-- 缺陷：V1 只在 sys_user.role_id 冗余列标记 admin 为角色 1，V6/V8 将全量权限
-- 绑定到 sys_role_permission(role_id=1)，但从未写入 sys_user_role(user_id=1, role_id=1)。
-- 而 getLoginUserById / selectPermsByUserId 均只从 sys_user_role 关联表加载角色与权限，
-- 且框架层无 roleId==1 超管回退、无 *:*:* 通配授予——结果种子 admin 登录成功但权限集为空，
-- 所有 @PreAuthorize 端点一律 403，开箱即坏的超级管理员。
--
-- 修复：补上 admin → 超级管理员 的用户-角色绑定（幂等）。
INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1)
ON CONFLICT (user_id, role_id) DO NOTHING;
