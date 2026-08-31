# Cloud-Infra 代码生成框架 (cloud-infra-codegen)

> 消除 RuoYi 风格手工 CRUD 样板代码的代码生成工具

## 模块组成

```
cloud-infra-codegen/                     # 聚合 pom
├── cloud-infra-codegen-core/            # 核心库 (JDK 25 编译, 完整功能)
└── cloud-infra-codegen-maven-plugin/    # Maven 插件 (JDK 17 编译, 自包含精简版)
```

> **设计说明**: Maven 3.x plugin loader 不支持 JDK 25 class file (major 69), 因此
> maven-plugin 子模块采用 JDK 17 编译并自包含必要代码, 端用户可在 JDK 25 项目中调用。

## 简介

`cloud-infra-codegen` 是 Cloud-Infra 平台的代码生成模块，自动生成：

- **Entity** - 数据库实体类（带 `@TableName`、`@TableId`、审计字段自动填充等）
- **Mapper** - MyBatis-Plus `BaseMapper` 接口
- **Create/Update DTO** - 校验注解的请求 DTO
- **VO** - 响应 VO 对象
- **Converter** - Entity ↔ DTO ↔ VO 转换器
- **Service 接口 + 实现** - `IService<T>` + `ServiceImpl<M, T>` 通用 CRUD
- **Controller** - REST 端点（含 `@PreAuthorize`、`@AuditLog`、`@Operation` 注解）
- **SQL** - 表结构 DDL

## 三种使用方式

### 1. Maven 插件 (推荐, 集成到构建流程)

```bash
# 在任意模块目录中:
mvn com.cloudinfra:cloud-infra-codegen-maven-plugin:1.0.0-SNAPSHOT:generate \
  -Dcodegen.url=jdbc:postgresql://localhost:5432/cloud_infra \
  -Dcodegen.username=root \
  -Dcodegen.password=secret \
  -Dcodegen.moduleName=system \
  -Dcodegen.tableNames=sys_user,sys_role
```

或通过 `pluginGroups` 配置简化（添加到 `~/.m2/settings.xml`）:
```xml
<pluginGroups>
  <pluginGroup>com.cloudinfra</pluginGroup>
</pluginGroups>
```

之后可简写为:
```bash
mvn codegen:generate -Dcodegen.url=... -Dcodegen.username=... -Dcodegen.password=... \
  -Dcodegen.moduleName=system -Dcodegen.tableNames=sys_user
```

### 2. CLI 命令行 (跨项目使用, 无需 Maven)

```bash
mvn -pl cloud-infra-codegen -am exec:java -Dexec.mainClass="com.cloudinfra.codegen.CodegenCli"
```

### 3. 编程式 API (在应用启动时调用)

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
DataSource ds = DataSourceFactory.create(props);

// 3. 读取表结构
TableMetadataReader reader = new TableMetadataReader(ds);
TableModel table = reader.readTable("file_metadata");

// 4. 生成代码
CodegenGenerator generator = new CodegenGenerator(config);
generator.generate(table);
```

## 完整示例

### 在子模块 pom.xml 中集成

```xml
<plugin>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-codegen-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <configuration>
        <url>${env.DB_URL}</url>
        <username>${env.DB_USER}</username>
        <password>${env.DB_PASSWORD}</password>
        <moduleName>file</moduleName>
        <tableNames>
            <tableName>file_metadata</tableName>
            <tableName>file_permission</tableName>
        </tableNames>
    </configuration>
</plugin>
```

```bash
DB_URL=jdbc:postgresql://localhost:5432/cloud_infra \
DB_USER=root DB_PASSWORD=secret \
mvn codegen:generate
```

### 一次生成全部表

```bash
mvn codegen:generate \
  -Dcodegen.url=jdbc:postgresql://localhost:5432/cloud_infra \
  -Dcodegen.username=root -Dcodegen.password=secret \
  -Dcodegen.moduleName=system \
  -Dcodegen.allTables=true
```

## 配置参数

| 参数 | 属性 | 必需 | 说明 |
|------|------|------|------|
| `url` | `codegen.url` | ✓ | JDBC URL (PostgreSQL) |
| `username` | `codegen.username` | ✓ | 数据库用户名 |
| `password` | `codegen.password` | ✓ | 数据库密码 |
| `moduleName` | `codegen.moduleName` | | 模块名，默认 `system` |
| `parent` | `codegen.parent` | | 父包，默认 `com.cloudinfra` |
| `tableNames` | `codegen.tableNames` | | 表名列表（逗号分隔或多次指定） |
| `allTables` | `codegen.allTables` | | 是否生成所有表 |
| `controllerPrefix` | `codegen.controllerPrefix` | | Controller 路径前缀，如 `/file` |
| `permissionPrefix` | `codegen.permissionPrefix` | | 权限前缀，如 `file` |
| `outputDir` | `codegen.outputDir` | | 输出根目录，默认 `${project.basedir}` |
| `skipIfExists` | `codegen.skipIfExists` | | 跳过已存在文件，默认 `true` |

## 项目结构

```
cloud-infra-codegen-core/                  # 核心库 (JDK 25)
├── src/
│   ├── main/
│   │   ├── java/com/cloudinfra/codegen/
│   │   │   ├── CodegenCli.java
│   │   │   ├── config/CodegenConfig.java
│   │   │   ├── generator/
│   │   │   │   ├── CodegenGenerator.java
│   │   │   │   └── TableMetadataReader.java
│   │   │   ├── model/{FieldModel,TableModel}.java
│   │   │   ├── template/TemplateEngine.java
│   │   │   └── util/DataSourceFactory.java
│   │   └── resources/templates/           # 10 个 FreeMarker 模板
│   │       ├── entity.java.ftl / mapper.java.ftl
│   │       ├── create_dto.java.ftl / update_dto.java.ftl
│   │       ├── vo.java.ftl / converter.java.ftl
│   │       ├── service.java.ftl / service_impl.java.ftl
│   │       ├── controller.java.ftl
│   │       └── schema.sql.ftl
│   └── test/                              # 14 个单元测试

cloud-infra-codegen-maven-plugin/         # Maven 插件 (JDK 17, 自包含)
├── src/main/
│   ├── java/com/cloudinfra/codegen/plugin/
│   │   └── GenerateCodeMojo.java
│   └── resources/templates/               # 5 个简化 FreeMarker 模板
│       ├── entity.java.tpl / mapper.java.tpl
│       ├── service.java.tpl / service_impl.java.tpl
│       └── controller.java.tpl
└── pom.xml
```

## 模板变量

### core 库模板 (`.ftl`)

| 变量 | 类型 | 说明 |
|------|------|------|
| `table` | TableModel | 当前表元数据 |
| `config` | CodegenConfig | 生成配置 |
| `fields` | List<FieldModel> | 表所有字段 |

### Maven 插件模板 (`.tpl`)

| 变量 | 类型 | 说明 |
|------|------|------|
| `t` | TableInfo | 表信息 |
| `package` | String | 包名 |
| `cp` | String | Controller 路径前缀 |
| `pp` | String | 权限前缀 |

## 依赖

- **JDK 25** (主项目) / **JDK 17** (maven-plugin 子模块)
- FreeMarker 2.3.33
- MyBatis-Plus 3.5.17
- PostgreSQL JDBC Driver
- Lombok (provided)
- Jackson

## 注意事项

- 平台统一使用 **PostgreSQL 16** (DataSourceFactory 仅支持 PostgreSQL)
- `skipIfExists=true` 默认开启，避免覆盖已有业务代码
- 生成的代码包含 MyBatis-Plus 审计字段自动填充 (`@TableField(fill = FieldFill.INSERT)`)
- `Lombok` 需要在 IDE 中安装插件

## 测试

```bash
mvn test -pl cloud-infra-codegen
```

测试覆盖:
- `CodegenGeneratorTest` (4 个) - 模板引擎和文件生成
- `CodegenConfigTest` (6 个) - 配置默认值和派生规则
- `ModelTest` (4 个) - 字段模型和表模型

## Roadmap

- [ ] 智能识别 Flyway DDL（避免连接真实 DB）
- [ ] OpenAPI 自动生成（基于 Controller 注解）
- [ ] 多数据库支持（MySQL, Oracle）
- [ ] VS Code 插件集成
- [ ] 模板市场（用户可下载/上传自定义模板）
