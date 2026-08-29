# ADR-010: 插件系统安全模型 — 明确仅 dev 用途, 生产禁用 hot-deploy

## 状态
2026-08-29 已决定

## 背景

`cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/plugin/` 提供
业务插件运行时, 包含 4 个核心类 + 2 个业务插件实现:

| 文件 | 职责 | 风险点 |
|---|---|---|
| `PluginManager.java` | 加载/卸载插件, 用 `URLClassLoader` 加载 jar | 任意 jar 路径可执行, 无签名校验 |
| `PluginRegistry.java` | 插件元数据注册表 | 配置文件可注入 |
| `PluginContextImpl.java` | 插件上下文 (服务查找) | 可访问全部 Spring bean |
| `HotDeploymentWatcher.java` | 文件变更触发热加载 | 远程访问可触发任意 jar 加载 |
| `impl/KnowledgeAssistantPlugin.java` | 业务实现 | 强业务耦合 |
| `impl/MediaAiTaggingPlugin.java` | 业务实现 | 强业务耦合 |

## 安全风险

1. **任意代码执行**: `URLClassLoader(urls, this.getClass().getClassLoader())` 接收任意 URL,
   可从 HTTP/FTP/NFS 加载 jar, 加载代码继承 system-module 全部权限
2. **无签名校验**: 不验证 jar 来源/签名, 中间人替换 jar 即可注入恶意代码
3. **沙箱缺失**: 插件可访问全部 Spring bean (通过 PluginContext), 可调任意 service
4. **无审计**: 加载/卸载/调用插件不写审计日志, 故障回溯困难
5. **文件系统触发**: `HotDeploymentWatcher` 监听目录, 任意文件写入触发重新加载

## 决策

**插件系统明确仅 dev/profile 用途, 生产 profile 默认禁用热加载**

### 实施细节

1. **`PluginManager` 加 `@ConditionalOnProperty(name = "cloud-infra.plugin.enabled", havingValue = "true", matchIfMissing = true)`**
   - dev profile: 默认 `true` (开启)
   - prod profile: 必须显式 `false` 才允许启用, 默认 `false`
2. **`HotDeploymentWatcher` 加 `@ConditionalOnProperty(name = "cloud-infra.plugin.hot-deploy", havingValue = "true", matchIfMissing = false)`**
   - 默认 `false` (禁用), dev profile 通过 `application-dev.yml` 显式开启
3. **生产配置模板** (`deploy/environments/prod/values-system.yaml`):
   ```yaml
   cloud-infra:
     plugin:
       enabled: false      # 生产禁用插件系统
       hot-deploy: false   # 生产禁用热加载
   ```
4. **加运行时启动检查**: 启动时若 `plugin.enabled=true` + `SPRING_PROFILES_ACTIVE=prod`,
   打印 WARN 日志: "Plugin system enabled in production profile, ensure proper sandboxing"
5. **加审计日志**: 插件加载/卸载/调用, 写 `sys_oper_log` 表 (复用现有 AuditLog 框架)

### 不做的事 (决策边界)

- **不引入 Java SecurityManager**: JDK 17+ 已 deprecated, JDK 25 完全移除
- **不引入 OSGi**: 重, 改造成本高, 与 Spring Boot 哲学不符
- **不做 jar 签名**: 维护私钥基础设施 ROI 低, 业务插件体量小
- **不做沙箱 (GraalVM isolate / jlink)**: 改造面太大, 与当前 HotSpot + 虚拟线程架构冲突

## 影响

### 受影响模块
- `cloud-infra-modules/system-module`
- `deploy/environments/prod/values-system.yaml`
- `deploy/environments/staging/values-system.yaml` (staging 同 prod 禁)
- `deploy/environments/dev/values-system.yaml` (dev 显式启用)

### 不影响
- 业务插件实现 (KnowledgeAssistantPlugin, MediaAiTaggingPlugin): 仍可被加载, 仅由 dev 控制台控制
- 其它框架模块

## 风险

- 业务方可能用插件做紧急修复绕过 review, 需 ADR 沟通避免滥用
- 启动时 WARN 日志可能让运维误以为配置错误, 需文档明示
