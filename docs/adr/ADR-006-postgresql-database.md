# ADR-006: PostgreSQL 作为主数据库

**状态**: 已接受  
**日期**: 2026-05-05  
**提出者**: 架构组  

## 背景

初期项目使用了 MySQL，但在评估数据库选型时发现 PostgreSQL 更适合本项目的架构需求。

## 备选方案

- **MySQL 8.4**: 中国生态成熟，但 JSONB 支持、窗口函数等高级功能不如 PostgreSQL
- **MySQL + MariaDB**: 双数据库方案增加运维复杂度
- **PostgreSQL 16**: 功能丰富，JSONB、窗口函数、全文检索、Partial Index 等特性满足微服务场景

## 决策

采用 **PostgreSQL 16** 作为主数据库，删除 MySQL/MariaDB 相关依赖和配置。

### 实施细节

1. 在 `deploy/config/*.yaml` 中统一使用 `jdbc:postgresql://` 连接字符串
2. 使用 `org.postgresql:postgresql:42.7.2` JDBC 驱动
3. 结合 Flyway 实现数据库版本管理（`flyway.version=10.15.0`）
4. 各业务模块使用 `schema.sql` 初始化对应数据库表
5. 删除 MySQL 特有的方言配置和 SQL 脚本

## 后果

**正面**:
- JSONB 数据类型支持灵活的元数据存储
- 优秀的并发控制（MVCC）
- 丰富的索引类型（Partial Index、GIN、GiST）
- 开源社区的活跃维护

**负面**:
- 运维经验要求高于 MySQL
- 部分国内云原生环境对 PostgreSQL 的支持不如 MySQL

## 关联

- 实施计划: `docs/superpowers/plans/2026-05-05-mysql-to-postgresql-migration.md`
- 相关代码: `schema.sql`, `application.yml` 数据源配置
