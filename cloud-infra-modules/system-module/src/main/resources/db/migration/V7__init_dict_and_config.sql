-- 字典类型表
CREATE TABLE IF NOT EXISTS sys_dict_type (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  dict_name varchar(100) NOT NULL COMMENT '字典名称',
  dict_type varchar(100) NOT NULL COMMENT '字典类型',
  status char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dict_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型表';

-- 字典数据表
CREATE TABLE IF NOT EXISTS sys_dict_data (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  dict_sort int DEFAULT '0' COMMENT '字典排序',
  dict_label varchar(100) DEFAULT '' COMMENT '字典标签',
  dict_value varchar(100) DEFAULT '' COMMENT '字典键值',
  dict_type varchar(100) DEFAULT '' COMMENT '字典类型',
  css_class varchar(100) DEFAULT NULL COMMENT '样式属性（其他业务扩展用）',
  list_class varchar(100) DEFAULT NULL COMMENT '表格回显样式',
  is_default char(1) DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  status char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典数据表';

-- 参数配置表
CREATE TABLE IF NOT EXISTS sys_config (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  config_name varchar(100) DEFAULT '' COMMENT '参数名称',
  config_key varchar(100) DEFAULT '' COMMENT '参数键名',
  config_value varchar(500) DEFAULT '' COMMENT '参数键值',
  config_type char(1) DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='参数配置表';

-- 初始数据
INSERT INTO sys_dict_type (dict_name, dict_type, remark) VALUES ('用户性别', 'sys_user_sex', '用户性别列表');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, list_class, is_default) VALUES (1, '男', '0', 'sys_user_sex', 'primary', 'Y');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, list_class, is_default) VALUES (2, '女', '1', 'sys_user_sex', 'danger', 'N');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, remark) VALUES ('用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y', '初始化密码 123456');
