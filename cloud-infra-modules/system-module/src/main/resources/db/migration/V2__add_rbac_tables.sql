CREATE TABLE IF NOT EXISTS sys_role (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  role_name varchar(50) NOT NULL COMMENT '角色名称',
  role_key varchar(50) NOT NULL COMMENT '角色权限字符',
  status char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_time datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色信息表';

CREATE TABLE IF NOT EXISTS sys_permission (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  name varchar(50) NOT NULL COMMENT '权限名称',
  perms varchar(100) DEFAULT NULL COMMENT '权限标识（如：user:add）',
  path varchar(200) DEFAULT NULL COMMENT '路由地址',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限信息表';

CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_id bigint NOT NULL COMMENT '角色ID',
  permission_id bigint NOT NULL COMMENT '权限ID',
  PRIMARY KEY (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';
