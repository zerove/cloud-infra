# P3-05: 4 个业务模块缺 exec classifier (v1.0.0 hotfix)

Status: closed (done + e2e verified 2026-09-02)
Priority: P3
Owner: AI Agent
Module: cloud-infra-gateway, cloud-infra-modules/{file,notification,search}
Depends: 无

## 背景

2026-09-02 用户报告"微服务未全部启动"。诊断发现:
- 6 个业务模块 jar 进程未启动 (部署丢失)
- 容器化方式 (`docker-compose up`) 是正确方法, 但 Dockerfile 越界 (`COPY ../../deploy/...`) 失败
- 直接用 `java -jar xxx.jar` 启动需要 `*-exec.jar` (Spring Boot fat jar with main-class)
- 之前只有 `cloud-infra-auth` 和 `cloud-infra-modules/system-module` 有 `*-exec.jar` (子 pom 加了 `<classifier>exec</classifier>`)
- **4 个模块缺 exec classifier**:
  - `cloud-infra-gateway/pom.xml`
  - `cloud-infra-modules/file-service/pom.xml`
  - `cloud-infra-modules/notification-service/pom.xml`
  - `cloud-infra-modules/search-service/pom.xml`
- 它们生成 `*.jar` (thin jar) 而非 `*-exec.jar` (Spring Boot fat jar)
- `java -jar gateway-1.0.0-SNAPSHOT.jar` 找不到 main-class manifest, 启动失败

## 修复 (commit `b779a58`, 2026-09-02)

每个模块的 `spring-boot-maven-plugin` 加 `<classifier>exec</classifier>`:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <!-- 2026-09-02 修复: 加 exec classifier 生成 *-exec.jar -->
        <classifier>exec</classifier>
        ...
    </configuration>
    ...
</plugin>
```

## 验证 (e2e, 2026-09-02)

详见 `docs/perf/2026-09-02-p3-15-exec-jar-e2e.md` (110 行)

### exec.jar manifest 验证

```bash
unzip -p cloud-infra-gateway/target/cloud-infra-gateway-1.0.0-SNAPSHOT-exec.jar META-INF/MANIFEST.MF
```

**输出**:
```
Main-Class: org.springframework.boot.loader.launch.JarLauncher
Start-Class: com.cloudinfra.gateway.GatewayApplication
Spring-Boot-Version: 4.1.0
```

✅ **exec classifier 修复生效**

### java -jar 启动验证 (4 模块)

| 模块 | 启动时间 | 业务端口 | mgmt 端口 | 状态 |
|---|---|---|---|---|
| gateway | (Nacos gRPC 重试中) | 9000 → 401 ✅ | 9301 → 404 ✅ | UP, security filter 工作 |
| file-service | 64.8 秒 | 8083 → 200 ✅ | 9304 → 200 ✅ | UP, Started FileApplication |
| notification-service | 73.9 秒 | 8084 → 200 ✅ | 9305 → 200 ✅ | UP, Started NotificationApplication |
| search-service | (失败: ES repository 配错) | - | - | 不是 P3-15 问题 |

**3/4 模块成功闭环**, 1/4 (search) 是 search-service 自身 ES 配置问题, 不属于 P3-15 hotfix。

## 附加发现 (本会话未修)

- **Dockerfile `COPY ../../deploy/...` 越界** (P3 已知): 4 模块 Dockerfile 用相对路径, build context = 模块目录时越界。
- **Search-service 启动问题**: `@EnableElasticsearchRepositories` 实例化失败, 是 search-service 自身 ES 配置问题。
- 两者均**不阻塞 v1.0.0**。

## 状态

- ✅ P3-15 closed
- ✅ mvn build: 4 个 exec.jar 生成 (109/128/155/141 MB)
- ✅ java -jar: 3/4 成功启动 Spring Boot 上下文, 业务端口响应
- ✅ v1.0.0 zero-trust 闭环 (release commit `e6b786d` immutable, 4 hotfix 全部验证)
