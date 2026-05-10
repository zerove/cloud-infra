-- 部门表
CREATE TABLE sys_dept (
  dept_id           bigint          NOT NULL,
  parent_id         bigint          DEFAULT 0,
  ancestors         varchar(50)     DEFAULT '',
  dept_name         varchar(30)     DEFAULT '',
  order_num         int             DEFAULT 0,
  leader            varchar(20)     DEFAULT NULL,
  phone             varchar(11)     DEFAULT NULL,
  email             varchar(50)     DEFAULT NULL,
  status            char(1)         DEFAULT '0',
  del_flag          char(1)         DEFAULT '0',
  create_by         varchar(64)     DEFAULT '',
  create_time 	    timestamp,
  update_by         varchar(64)     DEFAULT '',
  update_time       timestamp,
  PRIMARY KEY (dept_id)
);

-- 岗位表
CREATE TABLE sys_post (
  post_id           bigint          NOT NULL,
  post_code         varchar(64)     NOT NULL,
  post_name         varchar(50)     NOT NULL,
  post_sort         int             NOT NULL,
  status            char(1)         NOT NULL,
  create_by         varchar(64)     DEFAULT '',
  create_time       timestamp,
  update_by         varchar(64)     DEFAULT '',
  update_time       timestamp,
  remark            varchar(500)    DEFAULT NULL,
  PRIMARY KEY (post_id)
);

-- 用户与岗位关联表
CREATE TABLE sys_user_post (
  user_id           bigint          NOT NULL,
  post_id           bigint          NOT NULL,
  PRIMARY KEY (user_id, post_id)
);

-- 操作日志记录
CREATE TABLE sys_oper_log (
  oper_id           bigint          NOT NULL,
  title             varchar(50)     DEFAULT '',
  business_type     int             DEFAULT 0,
  method            varchar(100)    DEFAULT '',
  request_method    varchar(10)     DEFAULT '',
  operator_type     int             DEFAULT 0,
  oper_name         varchar(50)     DEFAULT '',
  dept_name         varchar(50)     DEFAULT '',
  oper_url          varchar(255)    DEFAULT '',
  oper_ip           varchar(128)    DEFAULT '',
  oper_location     varchar(255)    DEFAULT '',
  oper_param        text            DEFAULT '',
  json_result       text            DEFAULT '',
  status            int             DEFAULT 0,
  error_msg         text            DEFAULT '',
  oper_time         timestamp,
  cost_time         bigint          DEFAULT 0,
  PRIMARY KEY (oper_id)
);

-- 系统访问记录
CREATE TABLE sys_login_log (
  info_id           bigint          NOT NULL,
  user_name         varchar(50)     DEFAULT '',
  ipaddr            varchar(128)    DEFAULT '',
  login_location    varchar(255)    DEFAULT '',
  browser           varchar(50)     DEFAULT '',
  os                varchar(50)     DEFAULT '',
  status            char(1)         DEFAULT '0',
  msg               varchar(255)    DEFAULT '',
  login_time        timestamp,
  PRIMARY KEY (info_id)
);

-- 初始化部门数据
INSERT INTO sys_dept VALUES(100,  0,   '0',          '顶级部门', 0, 'Admin', '15888888888', 'admin@cloud.com', '0', '0', 'admin', now(), '', NULL);
INSERT INTO sys_dept VALUES(101,  100, '0,100',      '研发部门', 1, 'Dev',   '15888888888', 'dev@cloud.com',   '0', '0', 'admin', now(), '', NULL);
INSERT INTO sys_dept VALUES(102,  100, '0,100',      '市场部门', 2, 'Mark',  '15888888888', 'mark@cloud.com',  '0', '0', 'admin', now(), '', NULL);

-- 初始化岗位数据
INSERT INTO sys_post VALUES(1, 'ceo',  '董事长',    1, '0', 'admin', now(), '', NULL, '负责人');
INSERT INTO sys_post VALUES(2, 'se',   '项目经理',  2, '0', 'admin', now(), '', NULL, '项目经理');
INSERT INTO sys_post VALUES(3, 'hr',   '人力资源',  3, '0', 'admin', now(), '', NULL, 'HR');
INSERT INTO sys_post VALUES(4, 'user', '普通员工',  4, '0', 'admin', now(), '', NULL, '普通员工');
