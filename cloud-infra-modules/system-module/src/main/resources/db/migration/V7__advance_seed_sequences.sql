-- 推进 sys_role / sys_dept / sys_user 序列
-- V1 显式插入种子数据（id=1/2）未推进序列，导致后续 INSERT 生成 id 与种子数据冲突
-- 与 V6 中 sys_permission 的处理方式保持一致
SELECT setval(pg_get_serial_sequence('sys_role', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_role));
SELECT setval(pg_get_serial_sequence('sys_dept', 'dept_id'),
              (SELECT COALESCE(MAX(dept_id), 1) FROM sys_dept));
SELECT setval(pg_get_serial_sequence('sys_user', 'user_id'),
              (SELECT COALESCE(MAX(user_id), 1) FROM sys_user));