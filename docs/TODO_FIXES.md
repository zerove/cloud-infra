# 待修复问题记录


---
## [2026-04-27] 系统模块测试 Bean 注入冲突与 MyBatis 配置错误
- **报错现象**: 
  - `ConflictingBeanDefinitionException`: `GlobalExceptionHandler` Bean 定义冲突。
  - `UnsatisfiedDependencyException`: `RBACController` 依赖注入失败，MyBatis Mapper 无法注入 (`Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`)。
- **根本原因**: 测试上下文在加载 `SystemTestConfig` 时，未能正确扫描到 `MyBatisConfig` 定义的数据源和工厂配置，同时全局异常处理类存在多处定义引起冲突。
- **状态**: 待深度修复测试环境配置。
