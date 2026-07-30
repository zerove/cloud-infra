# ADR-005: Native Image 编译优化（Gateway 和 Auth 服务）

**状态**: 已接受  
**日期**: 2026-05-14  
**提出者**: 架构组  

## 背景

在 Kubernetes 环境中，服务的启动速度和内存占用直接影响弹性伸缩效率和资源成本。传统 JVM 应用启动时间通常在秒级（甚至分钟级），内存占用较高。

## 决策

对 **Gateway 和 Auth** 两个无状态网关/认证服务启用 GraalVM Native Image 编译，生成原生可执行文件。

### 实施细节

1. **构建工具**: GraalVM Native Build Tools（`native-maven-plugin` 0.10.3）
2. **AOT 配置**: 通过 `CloudInfraRuntimeHints` 注册反射、序列化所需类
3. **反射注册**: 自动扫描 `com.cloudinfra` 包下所有 `@TableName` 注解的实体类
4. **Profiles**: 使用 `-Pnative` 激活 AOT 编译
5. **构建脚本**: `build-native.sh`（建议内存 12GB+）

### 未启用 Native Image 的服务

- `system-module`、`file-service`、`notification-service`、`search-service` 等业务服务因：
  - 涉及大量动态代理（MyBatis Mapper）
  - 需要运行时 AOT 扫描
  - 对启动速度要求不如网关/认证严格
- 这些服务保持标准 JIT 模式运行

## 后果

**正面**:
- 启动时间从秒级降至毫秒级（<100ms）
- 内存占用减少约 60-70%
- K8s Pod 弹性伸缩响应更快

**负面**:
- 构建时间显著增加（需 AOT 编译）
- 需要维护 `reflection-config.json` 和 `RuntimeHintsRegistrar`
- 部分动态特性（反射、序列化）需提前声明
- 调试和问题排查更复杂

## 关联

- 实施计划: `docs/superpowers/plans/2026-05-14-full-native-image-migration.md`
- 设计文档: `docs/superpowers/specs/2026-05-14-full-native-image-migration-spec.md`
- 相关代码: `CloudInfraRuntimeHints`, `NativeConfiguration`, `reflect-config.json`
