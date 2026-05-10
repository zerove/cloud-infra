# 待修复问题记录


---
## [2026-04-27] 系统模块测试 Bean 注入冲突与 MyBatis 配置错误
- **报错现象**: 
  - `ConflictingBeanDefinitionException`: `GlobalExceptionHandler` Bean 定义冲突。
  - `UnsatisfiedDependencyException`: `RBACController` 依赖注入失败，MyBatis Mapper 无法注入 (`Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`)。
- **根本原因**: 测试上下文在加载 `SystemTestConfig` 时，未能正确扫描到 `MyBatisConfig` 定义的数据源和工厂配置，同时全局异常处理类存在多处定义引起冲突。
- **状态**: 已修复 (2026-04-28)
- **修复方案**:
  1. **Bean 冲突**: 给 `GlobalExceptionHandler` 增加 `@ConditionalOnMissingBean`，并在 `SystemTestConfig` 中显式排除 `SystemApplication` 扫描。
  2. **MyBatis 注入**: 将 `BaseIntegrationTest` 中的 MySQL 容器改为 `GenericContainer` 以避免 Testcontainers 的协议自动替换（解决 `jdbc:mariadb` 协议冲突），并手动构造 `jdbc:mysql` URL。
  3. **环境对齐**: 在 `system-module` 中补充 `spring-boot-starter-security` 依赖，并启用 `schema.sql` 自动初始化数据库表结构（通过合并现有 migration 脚本实现）。
