CREATE TABLE IF NOT EXISTS sys_user (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  username varchar(50) NOT NULL COMMENT '用户名',
  password varchar(100) NOT NULL COMMENT '密码',
  status char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

CREATE TABLE IF NOT EXISTS sys_role (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  role_name varchar(50) NOT NULL COMMENT '角色名称',
  role_key varchar(50) NOT NULL COMMENT '角色权限字符',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色信息表';

CREATE TABLE IF NOT EXISTS sys_user_role (
  user_id bigint NOT NULL COMMENT '用户ID',
  role_id bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';
