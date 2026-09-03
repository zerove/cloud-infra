# P3-06: M5-08 codegen 双模板整合 (前置: 删 codegen-core 对 common 的依赖)

Status: closed (done 2026-09-03)
Priority: P3
Owner: AI Agent
Module: cloud-infra-codegen/
Depends: 无 (前置已做)

## 背景

issue `05-codegen-template-unification.md` (M5-08): 整合 `cloud-infra-codegen-maven-plugin` 的 5 个 .tpl 模板与 `cloud-infra-codegen-core` 的 10 个 .ftl 模板 (维护成本翻倍)。

## 2026-09-03 探查发现: **前置依赖未做**

`cloud-infra-codegen-core` 在 .java 代码中 **0 处** `import com.cloudinfra.common.*`, 但 pom.xml 显式依赖 `cloud-infra-common`:

```xml
<dependency>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-common</artifactId>
</dependency>
```

`cloud-infra-common` 是 **JDK 25 编译 (major 69)**, `cloud-infra-codegen-core` 是 **JDK 17 编译 (major 61)**, `maven-plugin-plugin 3.13.0` 的 descriptor goal 不能分析 JDK 25 class (`Unsupported class file major version 69` 错误)。

如果让 maven-plugin 依赖 codegen-core, codegen-core 拖入 cloud-infra-common, 间接触发 JDK 25 错误。

## 修复 (2026-09-03 done)

### 删 `cloud-infra-codegen-core/pom.xml` 对 cloud-infra-common 的依赖

理由:
- core 0 个 java 文件 `import com.cloudinfra.common.*` (用 `grep -r "import com.cloudinfra.common" core/src/main/java` 验证)
- core 0 个测试文件用 common
- .ftl 模板字符串里 `import com.cloudinfra.common.core.domain.BaseEntity` 等是**用户责任** (用户用 codegen 生成代码时, 业务模块已依赖 common)
- 删依赖后 maven-plugin 复用 core 不会拖入 cloud-infra-common

### 验证

- mvn build core: ✅ BUILD SUCCESS, 25 tests pass, 1 skip (Testcontainers IT)
- mvn build maven-plugin (依赖 core): ✅ BUILD SUCCESS
- 端到端 generate sys_user: ✅ 输出 10 个文件 (含 DTO/VO/Converter/Controller/SQL)

## 完整 M5-08 改动

| 文件 | 改动 |
|---|---|
| `cloud-infra-codegen-core/pom.xml` | 删 cloud-infra-common 依赖 (-9 行) |
| `cloud-infra-codegen-maven-plugin/pom.xml` | 加 cloud-infra-codegen-core 依赖 (+9 行) |
| `GenerateCodeMojo.java` | 重写用 core API (319 → 145 行, -174 行) |
| 5 个 .tpl (entity/mapper/service/service_impl/controller) | 删除 (-98 行) |

**净减 262 行** (380 → 118 行, -69%)

## 引用

- M5-08 主 issue: `.scratch/2026-q3-roadmap/issues/05-codegen-template-unification.md` (同时 closed)
- 端到端验证报告: M5-08 issue 文件
- v1.0.0 tag `e6b786d` immutable 不动
