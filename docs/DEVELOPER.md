# 开发者指南

本指南提供参与 Cloud-Infra 项目开发所需的详细技术规范。

## 1. 项目结构

本项目采用 Maven 多模块构建：
- `cloud-infra-starter`: 项目引导模块。
- `cloud-infra-common`: 共享 POJO, 常量和基础工具类。
- `cloud-infra-framework`: 专项框架模块 (安全, Web, Redis 等)。
- `cloud-infra-api`: Feign 客户端和 DTO 定义。
- `cloud-infra-modules`: 业务微服务模块。

## 2. 编码标准

### Java 25 特性
- 所有 I/O 密集型操作必须使用 **虚拟线程 (Virtual Threads)**。
- 推荐使用 `var` 声明局部变量以减少样板代码。
- 在适用场景下使用现代 Switch 表达式和模式匹配。

### Lombok 使用规范
- DTO 和 Entity 统一使用 `@Data`。
- 推荐使用 `@RequiredArgsConstructor` 进行构造函数注入，避免在字段上使用 `@Autowired`。
- 日志记录统一使用 `@Slf4j`。

### MyBatis Plus
- 所有数据库实体必须继承 `BaseMapper<T>`。
- 使用 `LambdaQueryWrapper` 避免在查询中硬编码列名。
- 遵循 `Service` $ightarrow$ `ServiceImpl` $ightarrow$ `Mapper` 的三层架构模式。

## 3. 实现新功能流程

### 标准步骤
1. **定义 API**: 在 `cloud-infra-api` 中添加 DTO 和 Feign 客户端接口。
2. **实现逻辑**: 在 `cloud-infra-modules` 的相应模块中创建 Service 和 Controller。
3. **安全加固**: 使用 `@PreAuthorize("@ss.hasPermission('模块:操作')")` 保护接口方法。
4. **编写测试**: 在 `cloud-infra-test-support` 中创建继承自 `BaseIntegrationTest` 的集成测试。

### 权限集成
若要添加新权限：
1. 在 `sys_permission` 表中插入权限标识（例如 `system:user:edit`）。
2. 在 `sys_role_permission` 表中将该权限分配给相应角色。
3. 在代码中使用 `@ss` Bean (`PermissionService`) 校验该权限。

## 4. 配置管理

- **Nacos**: 所有业务配置和环境配置统一存储在 Nacos 中。
- **本地覆盖**: `application.yml` 仅用于引导设置（如 Nacos 地址、端口）。
- **密钥管理**: 敏感数值应使用 Jasypt 进行加密（框架层已配置）。

## 5. 测试策略

- **单元测试**: JUnit 5 + Mockito。
- **集成测试**: 使用 Testcontainers 在构建阶段启动真实的 PostgreSQL/Redis 实例。
- **验证**: 运行 `mvn test` 确保所有契约通过。
