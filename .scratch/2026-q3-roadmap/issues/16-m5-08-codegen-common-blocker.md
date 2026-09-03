# P3-06: M5-08 codegen 双模板整合 (前置: 删 codegen-core 对 common 的依赖)

Status: open (前置依赖未做)
Priority: P3
Owner: TBD
Module: cloud-infra-codegen/
Depends: cloud-infra-codegen-core 删除 cloud-infra-common 依赖

## 背景

issue `05-codegen-template-unification.md` (M5-08): 整合 `cloud-infra-codegen-maven-plugin` 的 5 个 .tpl 模板与 `cloud-infra-codegen-core` 的 10 个 .ftl 模板 (维护成本翻倍)。

## 2026-09-02 探查发现: **前置依赖未做**

`cloud-infra-codegen-core` 在 .java 代码中 **0 处** `import com.cloudinfra.common.*`, 但 pom.xml 显式依赖 `cloud-infra-common`:

```xml
<dependency>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-common</artifactId>
</dependency>
```

`cloud-infra-common` 是 **JDK 25 编译 (major 69)**, `cloud-infra-codegen-core` 是 **JDK 17 编译 (major 61)**, `maven-plugin-plugin 3.13.0` 的 descriptor goal 不能分析 JDK 25 class (`Unsupported class file major version 69` 错误)。

如果让 maven-plugin 依赖 codegen-core, codegen-core 拖入 cloud-infra-common, 间接触发 JDK 25 错误。

## 真正的修复路径

### 选项 A: 删 codegen-core 对 common 的依赖 (推荐)

```xml
<!-- 删除 cloud-infra-codegen-core/pom.xml -->
<dependency>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-common</artifactId>
</dependency>
```

模板字符串里的 `import com.cloudinfra.common.core.domain.BaseEntity` 等是**用户责任**(用户用 codegen 生成代码时,业务模块已依赖 common)。

**验证**: `grep -r "import com.cloudinfra.common" cloud-infra-codegen-core/src/main/java/` 应该返回 0 行 (只有 .ftl 文件用到)。

### 选项 B: 升级 maven-plugin-plugin 到 3.14+ (支持 JDK 25 class)

风险: 全项目 8+ 个 module 都受影响, 范围太大。

### 选项 C: 在 maven-plugin pom 用 `<classifier>exec>` 拉 codegen-core (但 core 没配, 不适用)

## 本会话 (2026-09-02) 探查结果

| 文件 | 状态 |
|---|---|
| GenerateCodeMojo.java | 已重写用 core API (175 行 → 76 行) |
| maven-plugin pom.xml | 已加 core 依赖 |
| 5 个 .tpl | 已删除 (entity/mapper/service/service_impl/controller) |

**但 build 失败** `Unsupported class file major version 69` — 因 codegen-core 拖入 cloud-infra-common (JDK 25)。

## 决定

**回滚所有改动** (本次 commit 范围超出本会话):
- 恢复 `GenerateCodeMojo.java` (重新内联 FreeMarker + .tpl)
- 恢复 `pom.xml` (移除 core 依赖)
- 恢复 5 个 .tpl 模板

代码保持原状, 真实修复需要:
1. 先做选项 A: 删 codegen-core 对 common 的依赖 (P3-06 前置)
2. 再做 M5-08: maven-plugin 用 core API + 删 5 个 .tpl

## 不阻塞 v1.0.0

v1.0.0 tag `e6b786d` 已发布, codegen 模块独立可用 (FlywayGenerateDemo + CodegenGenerator + core .ftl 模板都能工作)。

## 引用

- M5-08 原 issue: `.scratch/2026-q3-roadmap/issues/05-codegen-template-unification.md`
- 本会话探查: 2026-09-02 18:30 ~ 19:53
- 镜像 major version: `codegen-core` 编译为 major 61 (JDK 17), `cloud-infra-common` 编译为 major 69 (JDK 25)
- 错误: `Failed to analyze cloud-infra-common-1.0.0-SNAPSHOT.jar! com.cloudinfra.common.core.enums.SensitiveStrategy.class: Unsupported class file major version 69`
