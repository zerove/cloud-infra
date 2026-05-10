-- 为角色表添加数据范围字段
ALTER TABLE sys_role ADD COLUMN data_scope char(1) DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限）';

-- 为用户表添加部门ID
ALTER TABLE sys_user ADD COLUMN dept_id bigint DEFAULT NULL COMMENT '部门ID';

-- 角色部门关联表（自定数据权限时使用）
CREATE TABLE sys_role_dept (
  role_id   bigint NOT NULL,
  dept_id   bigint NOT NULL,
  PRIMARY KEY (role_id, dept_id)
);
