-- cloud-infra PostgreSQL 初始化脚本
--
-- 由 postgres 官方镜像在**首次**初始化数据目录时自动执行
-- (即 /docker-entrypoint-initdb.d/*.sql), 只跑一次。
--
-- 用途: SkyWalking OAP 的存储库。
--   OAP 自 2026-10-04 起从 elasticsearch 切换到 postgresql 存储
--   (原因见 deploy/compose.yaml 的 skywalking-oap 注释:
--    SkyWalking 全系不支持 ES 9, 而本仓库 ES 必须为 9.x 对齐 Spring Boot 4.1 客户端)。
--   OAP 的 JDBC provider **不会自己建库**, 连不上库就直接退出:
--     FATAL: database "skywalking" does not exist
--   故必须预先建好。本脚本只负责**空库**, 64 张表由 OAP 首次启动时自己建。
--
-- ⚠️ 两个易错点(都实测踩过):
--   1. SQL 注释是 `--`, 不是 `#`。用 # 会被当成语法错误。
--   2. PostgreSQL **没有** `CREATE DATABASE IF NOT EXISTS`(截至 16)。
--      幂等建库要用 psql 元命令 \gexec, 且它**必须另起一行**——
--      写成 `SELECT ... \gexec` 同一行, 反斜杠会被解析进 SQL 字符串里。
--
-- 正常路径下本脚本只会执行一次; \gexec 保证即便重复执行也不报错。

SELECT 'CREATE DATABASE skywalking'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'skywalking')
\gexec