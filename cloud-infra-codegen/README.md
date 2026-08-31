# Cloud-Infra 代码生成框架 (cloud-infra-codegen)

> 消除 RuoYi 风格手工 CRUD 样板代码的代码生成工具

## 简介

`cloud-infra-codegen` 是 Cloud-Infra 平台的代码生成模块，通过读取 PostgreSQL 数据库表结构自动生成：

- **Entity** - 数据库实体类（带 `@TableName`、`@TableId`、审计字段自动填充等）
- **Mapper** - MyBatis-Plus `BaseMapper` 接口
- **Create/Update DTO** - 校验注解的请求 DTO
- **VO** - 响应 VO 对象
- **Converter** - Entity ↔ DTO ↔ VO 转换器
- **Service 接口 + 实现** - `IService<T>` + `ServiceImpl<M, T>` 通用 CRUD
- **Controller** - REST 端点（含 `@PreAuthorize`、`@AuditLog`、`@Operation` 注解）
- **SQL** - 表结构 DDL

## 快速开始

### 编译模块

```bash
mvn clean install -pl cloud-infra-codegen -am -DskipTests
```

### 单元测试

```bash
mvn test -pl cloud-infra-codegen
```

### 命令行使用

```bash
mvn -pl cloud-infra-codegen exec:java -Dexec.mainClass="com.cloudinfra.codegen.CodegenCli"
```

CLI 提示依次输入:
1. 数据库 URL（如 `jdbc:postgresql://localhost:5432/cloud_infra`）
2. 数据库用户名
3. 数据库密码
4. 表名（多个用逗号分隔，或输入 `all`）
5. 模块名（`system`、`file`、`notification`、`search`）
6. 输出目录

### 编程式使用

```java
// 1. 配置
CodegenConfig config = new CodegenConfig();
config.setOutputDir("/path/to/project/");
config.setModuleName("file");
config.setPackageName("com.cloudinfra.file");
config.setControllerPrefix("/file");
config.setPermissionPrefix("file");

// 2. 创建数据源
Properties props = new Properties();
props.setProperty("url", "jdbc:postgresql://localhost:5432/cloud_infra");
props.setProperty("username", "root");
props.setProperty("password", "secret");
DataSource ds = DataSourceFactory.create(props);

// 3. 读取表结构
TableMetadataReader reader = new TableMetadataReader(ds);
TableModel table = reader.readTable("file_metadata");

// 4. 生成代码
CodegenGenerator generator = new CodegenGenerator(config);
generator.generate(table);
```

## 项目结构

```
cloud-infra-codegen/
├── src/
│   ├── main/
│   │   ├── java/com/cloudinfra/codegen/
│   │   │   ├── CodegenCli.java          # CLI 入口
│   │   │   ├── config/
│   │   │   │   └── CodegenConfig.java   # 生成配置
│   │   │   ├── generator/
│   │   │   │   ├── CodegenGenerator.java  # 主生成器
│   │   │   │   └── TableMetadataReader.java  # DB 元数据读取
│   │   │   ├── model/
│   │   │   │   ├── FieldModel.java      # 字段模型
│   │   │   │   └── TableModel.java      # 表模型
│   │   │   ├── template/
│   │   │   │   └── TemplateEngine.java  # FreeMarker 包装
│   │   │   └── util/
│   │   │       └── DataSourceFactory.java
│   │   └── resources/
│   │       └── templates/                # FreeMarker 模板
│   │           ├── entity.java.ftl
│   │           ├── mapper.java.ftl
│   │           ├── create_dto.java.ftl
│   │           ├── update_dto.java.ftl
│   │           ├── vo.java.ftl
│   │           ├── converter.java.ftl
│   │           ├── service.java.ftl
│   │           ├── service_impl.java.ftl
│   │           ├── controller.java.ftl
│   │           └── schema.sql.ftl
│   └── test/
│       └── java/com/cloudinfra/codegen/
│           ├── CodegenGeneratorTest.java
│           ├── config/CodegenConfigTest.java
│           └── model/ModelTest.java
└── pom.xml
```

## 模板变量

所有 FreeMarker 模板都可以使用以下变量：

| 变量 | 类型 | 说明 |
|------|------|------|
| `table` | TableModel | 当前表的元数据 |
| `config` | CodegenConfig | 代码生成配置 |
| `fields` | List<FieldModel> | 表所有字段 |

### TableModel 字段

- `className` - Java 类名（如 `SysUser`）
- `classAliasName` - 驼峰小写名（如 `sysUser`）
- `tableName` - 数据库表名
- `comment` - 表注释
- `primaryKey` - 主键字段
- `fields` - 字段列表
- `baseEntity` - 是否继承 BaseEntity

### FieldModel 字段

- `columnName` - 数据库列名
- `propertyName` - Java 属性名（驼峰）
- `javaType` - Java 类型完整名
- `jdbcType` - JDBC 类型
- `comment` - 字段注释
- `primaryKey` - 是否主键
- `notNull` - 是否非空
- `length` - 字段长度
- `fill` - 是否自动填充
- `fillStrategy` - 填充策略（`insert` / `update` / `insert_update`）
- `logicDelete` - 是否逻辑删除字段

## 配置选项

```java
CodegenConfig config = new CodegenConfig();
config.setOutputDir("/path/to/project/");
config.setModuleName("system");        // 模块名
config.setPackageName("com.cloudinfra.system");
config.setControllerPrefix("/system");
config.setPermissionPrefix("system");

// 按需关闭某些生成
config.setGenerateController(true);
config.setGenerateService(true);
config.setGenerateMapper(true);
config.setGenerateEntity(true);
config.setGenerateDto(true);
config.setGenerateVo(true);
config.setGenerateConverter(true);
config.setGenerateSql(true);
config.setSkipIfExists(true);  // 防止覆盖手写代码
```

## 依赖

- JDK 25
- FreeMarker 2.3.33
- MyBatis-Plus 3.5.17
- PostgreSQL JDBC Driver
- Lombok (provided)
- Jackson

## 注意事项

- 平台统一使用 **PostgreSQL 16**，不支持 MySQL（Cloud-Infra 已统一持久化方案）
- `skipIfExists=true` 默认开启，避免覆盖已有业务代码
- 模板基于现有 RuoYi 风格生成（Service 继承 `ServiceImpl`、Controller 包含完整注解）
- 生成的代码包含 `MyBatis-Plus 审计字段自动填充` 注解（`@TableField(fill = FieldFill.INSERT)`）

## 测试

```bash
mvn test -pl cloud-infra-codegen
```

测试覆盖:
- `CodegenGeneratorTest` (4 个) - 模板引擎和文件生成
- `CodegenConfigTest` (6 个) - 配置默认值和派生规则
- `ModelTest` (4 个) - 字段模型和表模型
